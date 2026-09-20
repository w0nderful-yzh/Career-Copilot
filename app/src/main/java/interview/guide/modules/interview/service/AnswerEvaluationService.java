package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewAnswerEntity.AnswerState;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.CategoryScore;
import interview.guide.modules.interview.model.InterviewReportDTO.QuestionEvaluation;
import interview.guide.modules.interview.model.InterviewReportDTO.ReferenceAnswer;
import interview.guide.modules.interview.model.InterviewReportDTO.TopicCoverage;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.interview.skill.InterviewSkillService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 文字面试答案评估服务。
 *
 * <p>LLM 只负责逐题语义评分与评语；P4-5 起，主问/追问分组、主题聚合、覆盖状态与总分
 * 全部由 Java 按版本化规则确定，避免可变路线下“追问越多权重越大”。
 */
@Service
public class AnswerEvaluationService {

  static final String SCORING_RULE_VERSION = "adaptive-report-v1";
  static final String AGGREGATION_METHOD =
      "主问占组内70%，追问均值占30%；仅一侧有评分时使用该侧；"
          + "主题内主问题组等权；跨主题等权；缺失评价不计0分";

  private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);

  private final UnifiedEvaluationService unifiedEvaluationService;
  private final InterviewPersistenceService persistenceService;
  private final InterviewSkillService skillService;

  public AnswerEvaluationService(
      UnifiedEvaluationService unifiedEvaluationService,
      InterviewPersistenceService persistenceService,
      InterviewSkillService skillService
  ) {
    this.unifiedEvaluationService = unifiedEvaluationService;
    this.persistenceService = persistenceService;
    this.skillService = skillService;
  }

  /** 兼容未声明必要覆盖的调用方。 */
  public InterviewReportDTO evaluateInterview(
      ChatClient chatClient,
      String sessionId,
      String resumeText,
      List<InterviewQuestionDTO> candidates,
      List<InterviewTurnDTO> turns
  ) {
    return evaluateInterview(chatClient, sessionId, resumeText, candidates, turns, List.of());
  }

  /**
   * 评估一场面试并生成可复算报告。
   *
   * <p>候选素材只提供题目元数据与覆盖范围，评分输入只含真实作答轮次；跳过、拒答与未回答
   * 仍进入报告轨迹，但不会进入 LLM 评分或数值聚合。
   */
  public InterviewReportDTO evaluateInterview(
      ChatClient chatClient,
      String sessionId,
      String resumeText,
      List<InterviewQuestionDTO> candidates,
      List<InterviewTurnDTO> turns,
      List<String> requiredTopics
  ) {
    log.info("开始评估面试: {}, 实际轮次={}, 候选素材={}", sessionId, turns.size(), candidates.size());

    try {
      List<InterviewTurnDTO> scoreableTurns = turns.stream()
          .filter(InterviewTurnDTO::countsAsAnswer)
          .toList();
      List<QaRecord> qaRecords = scoreableTurns.stream()
          .map(turn -> new QaRecord(turn.displayOrdinal() - 1, turn.question(), turn.category(),
              turn.userAnswer()))
          .toList();
      Set<String> scoreableIds = scoreableTurns.stream()
          .map(InterviewTurnDTO::questionId)
          .collect(Collectors.toCollection(LinkedHashSet::new));
      List<InterviewQuestionDTO> scoreableQuestions = candidates.stream()
          .filter(question -> scoreableIds.contains(question.questionId()))
          .toList();

      EvaluationReport semanticReport;
      if (scoreableTurns.isEmpty()) {
        // 全部跳过/拒答时没有语义评分输入：直接生成无分报告，避免为“空报告”调用模型。
        semanticReport = new EvaluationReport(
            sessionId, 0, null, List.of(), List.of(),
            "本场面试没有形成可评分答案，报告仅保留实际路线与覆盖状态。",
            List.of(), List.of(), List.of());
      } else {
        String referenceContext = buildQuestionReferenceContext(scoreableQuestions);
        if (referenceContext.isBlank()) {
          referenceContext = skillService.buildEvaluationReferenceSectionSafe(
              persistenceService.findBySessionId(sessionId)
                  .map(s -> s.getSkillId())
                  .orElse(null)
          );
        }
        semanticReport = unifiedEvaluationService.evaluate(
            chatClient, sessionId, qaRecords, resumeText, referenceContext);
      }
      return assembleReport(semanticReport, candidates, turns, requiredTopics);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("面试评估失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
          "面试评估失败：" + e.getMessage());
    }
  }

  /** P4-5 确定性报告组装；包内可见，便于用固定输入验证可重算。 */
  InterviewReportDTO assembleReport(
      EvaluationReport semanticReport,
      List<InterviewQuestionDTO> candidates,
      List<InterviewTurnDTO> turns,
      List<String> requiredTopics
  ) {
    Map<String, InterviewQuestionDTO> candidatesById = candidates.stream()
        .filter(question -> hasText(question.questionId()))
        .collect(Collectors.toMap(
            InterviewQuestionDTO::questionId,
            Function.identity(),
            (first, ignored) -> first,
            LinkedHashMap::new));
    Map<Integer, EvaluationReport.QuestionEvaluation> evaluationsByIndex =
        semanticReport.questionDetails().stream()
            .collect(Collectors.toMap(
                EvaluationReport.QuestionEvaluation::questionIndex,
                Function.identity(),
                (first, ignored) -> first));
    Map<Integer, EvaluationReport.ReferenceAnswer> referencesByIndex =
        semanticReport.referenceAnswers().stream()
            .collect(Collectors.toMap(
                EvaluationReport.ReferenceAnswer::questionIndex,
                Function.identity(),
                (first, ignored) -> first));

    List<TopicScope> scopes = buildTopicScopes(candidates, turns, requiredTopics);
    List<QuestionEvaluation> details = turns.stream()
        .filter(InterviewTurnDTO::isTurn)
        .map(turn -> toQuestionEvaluation(
            turn,
            candidatesById.get(turn.questionId()),
            evaluationsByIndex.get(turn.displayOrdinal() - 1),
            scopes))
        .toList();

    List<RouteScore> routeScores = buildRouteScores(details);
    List<CategoryScore> categoryScores = buildCategoryScores(scopes, details, routeScores);
    List<Integer> assessedTopics = categoryScores.stream()
        .map(CategoryScore::score)
        .filter(score -> score != null)
        .toList();
    Integer overallScore = assessedTopics.isEmpty() ? null : roundedAverage(assessedTopics);
    List<TopicCoverage> coverage = buildCoverage(scopes, details, routeScores);
    List<ReferenceAnswer> references = turns.stream()
        .filter(InterviewTurnDTO::isTurn)
        .map(turn -> toReferenceAnswer(
            turn,
            candidatesById.get(turn.questionId()),
            referencesByIndex.get(turn.displayOrdinal() - 1)))
        .toList();

    return new InterviewReportDTO(
        semanticReport.sessionId(),
        details.size(),
        overallScore,
        categoryScores,
        details,
        semanticReport.overallFeedback(),
        nullSafe(semanticReport.strengths()),
        nullSafe(semanticReport.improvements()),
        references,
        SCORING_RULE_VERSION,
        AGGREGATION_METHOD,
        coverage,
        coverage.stream()
            .filter(item -> "NOT_ASSESSED".equals(item.status()))
            .map(TopicCoverage::topic)
            .toList(),
        details.stream()
            .filter(item -> AnswerState.SKIPPED.name().equals(item.answerState()))
            .map(QuestionEvaluation::questionId)
            .toList(),
        details.stream()
            .filter(item -> AnswerState.ANSWERED.name().equals(item.answerState()))
            .filter(item -> item.score() == null)
            .map(QuestionEvaluation::questionId)
            .toList()
    );
  }

  private QuestionEvaluation toQuestionEvaluation(
      InterviewTurnDTO turn,
      InterviewQuestionDTO question,
      EvaluationReport.QuestionEvaluation evaluation,
      List<TopicScope> scopes
  ) {
    boolean answered = turn.countsAsAnswer();
    Integer score = answered && evaluation != null ? clampScore(evaluation.score()) : null;
    String feedback = feedbackFor(turn, evaluation, score);
    String topic = canonicalTopic(question, turn, scopes);
    boolean followUp = question != null && question.isFollowUp();
    return new QuestionEvaluation(
        turn.displayOrdinal() - 1,
        turn.questionId(),
        turn.ordinal(),
        turn.question(),
        turn.category(),
        topic,
        followUp,
        question != null ? question.parentQuestionId() : null,
        question != null ? question.difficulty() : null,
        expectedPoints(question),
        turn.userAnswer(),
        turn.answerState() != null ? turn.answerState().name() : AnswerState.ANSWERED.name(),
        score,
        feedback,
        turn.decidedAction(),
        turn.decisionReason(),
        comparisonFor(turn, score)
    );
  }

  private static String feedbackFor(
      InterviewTurnDTO turn,
      EvaluationReport.QuestionEvaluation evaluation,
      Integer score
  ) {
    if (!turn.countsAsAnswer()) {
      return switch (turn.answerState()) {
        case SKIPPED -> "用户主动跳过，本轮不计分。";
        case DECLINED -> "用户明确表示不会，本轮作为诊断事实保留但不计分。";
        case UNANSWERED -> "本轮未作答，不计分。";
        case ANSWERED -> "";
        case null -> "本轮未形成可评分答案。";
      };
    }
    if (score == null) {
      return "正式报告未获得有效评分，证据不足，本轮不计入聚合。";
    }
    return evaluation.feedback() != null ? evaluation.feedback() : "";
  }

  private static String comparisonFor(InterviewTurnDTO turn, Integer score) {
    if (!hasText(turn.decidedAction()) && !hasText(turn.decisionReason())) {
      return null;
    }
    if (!turn.countsAsAnswer()) {
      return "实时决策仅推进面试路线；本轮没有技术评分。";
    }
    if (score == null) {
      return "实时判断已用于选择下一步；正式报告没有有效评分，未将缺失评价补成0分。";
    }
    return "实时判断用于选择下一步，正式报告在面试结束后独立评分；两者用途不同。";
  }

  private static ReferenceAnswer toReferenceAnswer(
      InterviewTurnDTO turn,
      InterviewQuestionDTO question,
      EvaluationReport.ReferenceAnswer evaluated
  ) {
    String reference = question != null && hasText(question.referenceAnswer())
        ? question.referenceAnswer()
        : evaluated != null ? evaluated.referenceAnswer() : "";
    List<String> keyPoints = question != null && question.keyPoints() != null
        && !question.keyPoints().isEmpty()
        ? question.keyPoints()
        : evaluated != null ? nullSafe(evaluated.keyPoints()) : List.of();
    return new ReferenceAnswer(
        turn.displayOrdinal() - 1,
        turn.question(),
        reference != null ? reference : "",
        keyPoints);
  }

  private static List<RouteScore> buildRouteScores(List<QuestionEvaluation> details) {
    Map<RouteKey, List<QuestionEvaluation>> byRoute = new LinkedHashMap<>();
    for (QuestionEvaluation detail : details) {
      String rootId = detail.followUp() && hasText(detail.parentQuestionId())
          ? detail.parentQuestionId()
          : detail.questionId();
      byRoute.computeIfAbsent(new RouteKey(detail.topic(), rootId), ignored -> new ArrayList<>())
          .add(detail);
    }
    return byRoute.entrySet().stream()
        .map(entry -> new RouteScore(
            entry.getKey().topic(),
            routeScore(entry.getValue())))
        .toList();
  }

  private static Integer routeScore(List<QuestionEvaluation> details) {
    Integer mainScore = details.stream()
        .filter(detail -> !detail.followUp())
        .map(QuestionEvaluation::score)
        .filter(score -> score != null)
        .findFirst()
        .orElse(null);
    List<Integer> followUpScores = details.stream()
        .filter(QuestionEvaluation::followUp)
        .map(QuestionEvaluation::score)
        .filter(score -> score != null)
        .toList();
    if (mainScore == null && followUpScores.isEmpty()) {
      return null;
    }
    if (mainScore == null) {
      return roundedAverage(followUpScores);
    }
    if (followUpScores.isEmpty()) {
      return mainScore;
    }
    return (int) Math.round(mainScore * 0.7 + roundedAverage(followUpScores) * 0.3);
  }

  private static List<CategoryScore> buildCategoryScores(
      List<TopicScope> scopes,
      List<QuestionEvaluation> details,
      List<RouteScore> routes
  ) {
    return scopes.stream().map(scope -> {
      List<RouteScore> topicRoutes = routes.stream()
          .filter(route -> scope.label().equals(route.topic()))
          .toList();
      List<Integer> scores = topicRoutes.stream()
          .map(RouteScore::score)
          .filter(score -> score != null)
          .toList();
      int questionCount = (int) details.stream()
          .filter(detail -> scope.label().equals(detail.topic()))
          .count();
      return new CategoryScore(
          scope.label(),
          scores.isEmpty() ? null : roundedAverage(scores),
          questionCount,
          topicRoutes.size(),
          scores.size(),
          "主问题组等权；每组主问70%，全部追问均值30%；仅一侧有评分时使用该侧"
      );
    }).toList();
  }

  private static List<TopicCoverage> buildCoverage(
      List<TopicScope> scopes,
      List<QuestionEvaluation> details,
      List<RouteScore> routes
  ) {
    return scopes.stream().map(scope -> {
      List<QuestionEvaluation> topicDetails = details.stream()
          .filter(detail -> scope.label().equals(detail.topic()))
          .toList();
      int answered = (int) topicDetails.stream()
          .filter(detail -> AnswerState.ANSWERED.name().equals(detail.answerState()))
          .count();
      int skipped = (int) topicDetails.stream()
          .filter(detail -> AnswerState.SKIPPED.name().equals(detail.answerState()))
          .count();
      int evaluatedGroups = (int) routes.stream()
          .filter(route -> scope.label().equals(route.topic()))
          .filter(route -> route.score() != null)
          .count();
      String status;
      if (topicDetails.isEmpty()) {
        status = "NOT_ASSESSED";
      } else if (answered == 0) {
        status = "SKIPPED";
      } else if (evaluatedGroups == 0) {
        status = "INSUFFICIENT_EVIDENCE";
      } else {
        status = "ASSESSED";
      }
      return new TopicCoverage(
          scope.label(),
          scope.required(),
          status,
          topicDetails.size(),
          answered,
          skipped,
          evaluatedGroups,
          topicDetails.stream().map(QuestionEvaluation::questionId).toList(),
          topicDetails.stream()
              .flatMap(detail -> detail.expectedPoints().stream())
              .filter(AnswerEvaluationService::hasText)
              .distinct()
              .toList()
      );
    }).toList();
  }

  private static List<TopicScope> buildTopicScopes(
      List<InterviewQuestionDTO> candidates,
      List<InterviewTurnDTO> turns,
      List<String> requiredTopics
  ) {
    List<TopicScope> scopes = new ArrayList<>();
    for (String required : nullSafe(requiredTopics)) {
      if (!hasText(required)) {
        continue;
      }
      InterviewQuestionDTO matched = candidates.stream()
          .filter(InterviewQuestionDTO::isMain)
          .filter(question -> question.matchesTopic(required))
          .findFirst()
          .orElse(null);
      addScope(scopes, new TopicScope(required, displayTopic(matched, required), true));
    }
    for (InterviewQuestionDTO candidate : candidates) {
      if (candidate.isMain()) {
        String label = displayTopic(candidate, "其他");
        addScope(scopes, new TopicScope(label, label, false));
      }
    }
    for (InterviewTurnDTO turn : turns) {
      String label = displayTopic(null, turn.topic() != null ? turn.topic() : turn.category());
      addScope(scopes, new TopicScope(label, label, false));
    }
    return scopes;
  }

  private static void addScope(List<TopicScope> scopes, TopicScope candidate) {
    for (int i = 0; i < scopes.size(); i++) {
      TopicScope existing = scopes.get(i);
      if (existing.label().equalsIgnoreCase(candidate.label())
          || existing.matchKey().equalsIgnoreCase(candidate.matchKey())) {
        if (candidate.required() && !existing.required()) {
          scopes.set(i, new TopicScope(existing.matchKey(), existing.label(), true));
        }
        return;
      }
    }
    scopes.add(candidate);
  }

  private static String canonicalTopic(
      InterviewQuestionDTO question,
      InterviewTurnDTO turn,
      List<TopicScope> scopes
  ) {
    if (question != null) {
      for (TopicScope scope : scopes) {
        if (question.matchesTopic(scope.matchKey())
            || displayTopic(question, "其他").equalsIgnoreCase(scope.label())) {
          return scope.label();
        }
      }
      return displayTopic(question, turn.category());
    }
    return displayTopic(null, turn.topic() != null ? turn.topic() : turn.category());
  }

  private static String displayTopic(InterviewQuestionDTO question, String fallback) {
    if (question != null) {
      if (hasText(question.topic())) {
        return question.topic().trim();
      }
      if (hasText(question.category())) {
        return question.category().trim();
      }
      if (hasText(question.type())) {
        return question.type().trim();
      }
    }
    return hasText(fallback) ? fallback.trim() : "其他";
  }

  private static List<String> expectedPoints(InterviewQuestionDTO question) {
    if (question == null) {
      return List.of();
    }
    if (question.expectedPoints() != null && !question.expectedPoints().isEmpty()) {
      return question.expectedPoints();
    }
    return nullSafe(question.keyPoints());
  }

  private String buildQuestionReferenceContext(List<InterviewQuestionDTO> questions) {
    StringBuilder sb = new StringBuilder();
    for (InterviewQuestionDTO question : questions) {
      if (!hasQuestionReference(question)) {
        continue;
      }
      sb.append("问题").append(question.questionIndex() + 1).append(": ")
          .append(question.question()).append('\n');
      appendIfPresent(sb, "参考答案", question.referenceAnswer());
      if (question.keyPoints() != null && !question.keyPoints().isEmpty()) {
        sb.append("评分要点: ").append(String.join("；", question.keyPoints())).append('\n');
      }
      appendIfPresent(sb, "评分规则", question.scoringRubric());
      sb.append('\n');
    }
    return sb.toString();
  }

  private boolean hasQuestionReference(InterviewQuestionDTO question) {
    return hasText(question.referenceAnswer())
        || hasText(question.scoringRubric())
        || (question.keyPoints() != null && !question.keyPoints().isEmpty());
  }

  private void appendIfPresent(StringBuilder sb, String label, String value) {
    if (hasText(value)) {
      sb.append(label).append(": ").append(value.trim()).append('\n');
    }
  }

  private static Integer clampScore(Integer score) {
    return score == null ? null : Math.max(0, Math.min(100, score));
  }

  private static int roundedAverage(List<Integer> scores) {
    return (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElseThrow());
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static <T> List<T> nullSafe(List<T> values) {
    return values != null ? values : List.of();
  }

  private record TopicScope(String matchKey, String label, boolean required) {}

  private record RouteKey(String topic, String rootQuestionId) {}

  private record RouteScore(String topic, Integer score) {}
}
