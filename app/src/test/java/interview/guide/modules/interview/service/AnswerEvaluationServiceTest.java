package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.skill.InterviewSkillService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnswerEvaluationServiceTest {

  @Mock
  private UnifiedEvaluationService unifiedEvaluationService;

  @Mock
  private InterviewPersistenceService persistenceService;

  @Mock
  private InterviewSkillService skillService;

  @Test
  @DisplayName("题目自带评分依据时不再拼接 Skill 参考基线")
  void shouldSkipSkillReferenceWhenQuestionsHaveReferences() {
    AnswerEvaluationService service = new AnswerEvaluationService(
        unifiedEvaluationService,
        persistenceService,
        skillService
    );
    InterviewQuestionDTO question = InterviewQuestionDTO.fromQuestionBank(
        0,
        "什么是索引下推？",
        "MYSQL",
        "MySQL",
        "索引优化",
        "参考答案",
        List.of("评分要点"),
        "评分规则",
        "来源片段"
    );
    InterviewQuestionDTO unasked = InterviewQuestionDTO.fromQuestionBank(
        1, "未问题", "MYSQL", "MySQL", "无关候选", "不应进入上下文",
        List.of("无关要点"), "无关规则", "无关来源");
    when(unifiedEvaluationService.evaluate(
        nullable(ChatClient.class), eq("session1"), any(), eq("简历"), any()
    )).thenReturn(report());

    // P4-1：报告吃「候选素材 + 实际轨迹」两份输入——素材供参考答案，轨迹是评分对象
    InterviewTurnDTO answered = new InterviewTurnDTO(question.questionId(), 1,
        question.questionIndex(), question.question(), question.category(), null, "回答",
        InterviewAnswerEntity.AnswerState.ANSWERED, null, null,
        InterviewTurnDTO.ACTION_FINISH_EXHAUSTED, null, List.of(), null);
    InterviewReportDTO actual = service.evaluateInterview(
        null, "session1", "简历", List.of(question, unasked), List.of(answered));

    ArgumentCaptor<String> referenceCaptor = ArgumentCaptor.forClass(String.class);
    verify(unifiedEvaluationService).evaluate(
        nullable(ChatClient.class), eq("session1"), any(), eq("简历"), referenceCaptor.capture()
    );
    verify(skillService, never()).buildEvaluationReferenceSectionSafe(any());
    verify(persistenceService, never()).findBySessionId(any());
    assertThat(referenceCaptor.getValue())
        .contains("参考答案")
        .contains("评分要点")
        .contains("评分规则")
        .doesNotContain("不应进入上下文")
        .doesNotContain("来源片段");
    assertThat(actual.referenceAnswers().getFirst().referenceAnswer()).isEqualTo("参考答案");
  }

  @Test
  @DisplayName("报告只评分真实作答，跳过轮次保留在轨迹但不进入总分")
  @SuppressWarnings("unchecked")
  void shouldExcludeSkippedTurnsFromEvaluation() {
    AnswerEvaluationService service = new AnswerEvaluationService(
        unifiedEvaluationService, persistenceService, skillService);
    InterviewQuestionDTO skippedQuestion = InterviewQuestionDTO.create(
        0, "跳过题", "JAVA", "Java").withQuestionId("q-skip");
    InterviewQuestionDTO answeredQuestion = InterviewQuestionDTO.create(
        1, "作答题", "JAVA", "Java").withQuestionId("q-answer");
    List<InterviewTurnDTO> turns = List.of(
        new InterviewTurnDTO("q-skip", 1, 0, "跳过题", "Java", null, null,
            InterviewAnswerEntity.AnswerState.SKIPPED, null, null,
            InterviewTurnDTO.ACTION_NEXT_MAIN, null, List.of(), null),
        new InterviewTurnDTO("q-answer", 2, 1, "作答题", "Java", null, "有效答案",
            InterviewAnswerEntity.AnswerState.ANSWERED, null, null,
            InterviewTurnDTO.ACTION_FINISH_EXHAUSTED, null, List.of(), null));
    ArgumentCaptor<List<QaRecord>> recordsCaptor =
        ArgumentCaptor.forClass(List.class);
    when(unifiedEvaluationService.evaluate(
        nullable(ChatClient.class), eq("session2"), any(), eq(""), any()
    )).thenReturn(new EvaluationReport(
        "session2", 1, 80, List.of(), List.of(), "", List.of(), List.of(), List.of()));

    service.evaluateInterview(
        null, "session2", "", List.of(skippedQuestion, answeredQuestion), turns);

    verify(unifiedEvaluationService).evaluate(
        nullable(ChatClient.class), eq("session2"), recordsCaptor.capture(), eq(""), any());
    assertThat(recordsCaptor.getValue())
        .singleElement()
        .satisfies(record -> {
          assertThat(record.questionIndex()).isEqualTo(1);
          assertThat(record.userAnswer()).isEqualTo("有效答案");
        });
  }

  @Test
  @DisplayName("主问组与主题等权聚合，追加追问不会放大该路线权重")
  void shouldAggregateVariableRoutesByMainGroupAndTopic() {
    AnswerEvaluationService service = new AnswerEvaluationService(
        unifiedEvaluationService, persistenceService, skillService);
    InterviewQuestionDTO jvmMain = main(0, "jvm-main", "JVM", "JVM 主问");
    InterviewQuestionDTO jvmFollow1 = followUp(1, "jvm-f1", "JVM", jvmMain.questionId());
    InterviewQuestionDTO jvmFollow2 = followUp(2, "jvm-f2", "JVM", jvmMain.questionId());
    InterviewQuestionDTO jvmSecond = main(3, "jvm-second", "JVM", "JVM 第二主问");
    InterviewQuestionDTO redisMain = main(4, "redis-main", "Redis", "Redis 主问");
    List<InterviewQuestionDTO> candidates =
        List.of(jvmMain, jvmFollow1, jvmFollow2, jvmSecond, redisMain);
    List<InterviewTurnDTO> turns = List.of(
        answered(jvmMain, 1),
        answered(jvmFollow1, 2),
        answered(jvmFollow2, 3),
        answered(jvmSecond, 4),
        answered(redisMain, 5));
    EvaluationReport semantic = semanticReport("route-session", List.of(
        evaluated(0, jvmMain, 60),
        evaluated(1, jvmFollow1, 100),
        evaluated(2, jvmFollow2, 100),
        evaluated(3, jvmSecond, 80),
        evaluated(4, redisMain, 40)));

    InterviewReportDTO actual = service.assembleReport(
        semantic, candidates, turns, List.of("JVM", "Redis"));

    Map<String, InterviewReportDTO.CategoryScore> scores = actual.categoryScores().stream()
        .collect(Collectors.toMap(InterviewReportDTO.CategoryScore::category, Function.identity()));
    // JVM 第一组 = 60*70% + avg(100,100)*30% = 72；第二组 = 80；主题均值 = 76。
    assertThat(scores.get("JVM").score()).isEqualTo(76);
    assertThat(scores.get("JVM").mainGroupCount()).isEqualTo(2);
    assertThat(scores.get("JVM").evaluatedMainGroupCount()).isEqualTo(2);
    assertThat(scores.get("Redis").score()).isEqualTo(40);
    // 跨主题等权：(76 + 40) / 2 = 58；不是五个轮次直接平均得到的 76。
    assertThat(actual.overallScore()).isEqualTo(58);
    assertThat(actual.scoringRuleVersion())
        .isEqualTo(AnswerEvaluationService.SCORING_RULE_VERSION);
    assertThat(service.assembleReport(semantic, candidates, turns, List.of("JVM", "Redis")))
        .as("同一规则版本与同一输入必须可重算出完全相同的报告")
        .isEqualTo(actual);
  }

  @Test
  @DisplayName("未考察、跳过和正式评估证据不足独立表达，缺失分数不补零")
  void shouldKeepUnassessedSkippedAndMissingEvaluationDistinct() {
    AnswerEvaluationService service = new AnswerEvaluationService(
        unifiedEvaluationService, persistenceService, skillService);
    InterviewQuestionDTO jvm = main(0, "jvm", "JVM", "JVM 主问");
    InterviewQuestionDTO redis = main(1, "redis", "Redis", "Redis 主问");
    InterviewQuestionDTO mq = main(2, "mq", "MQ", "MQ 主问");
    InterviewTurnDTO answeredWithoutReport = new InterviewTurnDTO(
        jvm.questionId(), 1, jvm.questionIndex(), jvm.question(), jvm.category(), jvm.topic(),
        "回答过，但正式评估不可用", InterviewAnswerEntity.AnswerState.ANSWERED,
        null, null, InterviewTurnDTO.ACTION_NEXT_MAIN, null, List.of(), null,
        redis.questionId(), "实时判断认为需要转场", null);
    InterviewTurnDTO skipped = new InterviewTurnDTO(
        redis.questionId(), 2, redis.questionIndex(), redis.question(), redis.category(), redis.topic(),
        null, InterviewAnswerEntity.AnswerState.SKIPPED,
        null, null, InterviewTurnDTO.ACTION_FINISH_EXHAUSTED, null, List.of(), null);
    EvaluationReport semantic = semanticReport("missing-session", List.of());

    InterviewReportDTO actual = service.assembleReport(
        semantic, List.of(jvm, redis, mq), List.of(answeredWithoutReport, skipped),
        List.of("JVM", "Redis", "MQ"));

    assertThat(actual.overallScore()).isNull();
    assertThat(actual.insufficientEvidenceQuestionIds()).containsExactly("jvm");
    assertThat(actual.skippedQuestionIds()).containsExactly("redis");
    assertThat(actual.unassessedTopics()).containsExactly("MQ");
    Map<String, String> coverage = actual.coverage().stream()
        .collect(Collectors.toMap(
            InterviewReportDTO.TopicCoverage::topic,
            InterviewReportDTO.TopicCoverage::status));
    assertThat(coverage)
        .containsEntry("JVM", "INSUFFICIENT_EVIDENCE")
        .containsEntry("Redis", "SKIPPED")
        .containsEntry("MQ", "NOT_ASSESSED");
    assertThat(actual.questionDetails().getFirst().score()).isNull();
    assertThat(actual.questionDetails().getFirst().difficulty()).isEqualTo(3);
    assertThat(actual.questionDetails().getFirst().expectedPoints()).containsExactly("JVM 要点");
    assertThat(actual.questionDetails().getFirst().decisionReason())
        .isEqualTo("实时判断认为需要转场");
    assertThat(actual.questionDetails().getFirst().decisionComparison())
        .contains("未将缺失评价补成0分");
  }

  @Test
  @DisplayName("整场均为跳过时直接生成无分报告，不为无评分输入调用模型")
  void shouldBuildEmptyScoreReportWithoutLlmCall() {
    AnswerEvaluationService service = new AnswerEvaluationService(
        unifiedEvaluationService, persistenceService, skillService);
    InterviewQuestionDTO question = main(0, "skip-only", "JVM", "JVM 主问");
    InterviewTurnDTO skipped = new InterviewTurnDTO(
        question.questionId(), 1, question.questionIndex(), question.question(),
        question.category(), question.topic(), null, InterviewAnswerEntity.AnswerState.SKIPPED,
        null, null, InterviewTurnDTO.ACTION_FINISH_EXHAUSTED, null, List.of(), null);

    InterviewReportDTO actual = service.evaluateInterview(
        null, "skip-session", "", List.of(question), List.of(skipped), List.of("JVM"));

    assertThat(actual.overallScore()).isNull();
    assertThat(actual.skippedQuestionIds()).containsExactly("skip-only");
    assertThat(actual.coverage().getFirst().status()).isEqualTo("SKIPPED");
    verifyNoInteractions(unifiedEvaluationService, persistenceService, skillService);
  }

  private static InterviewQuestionDTO main(int index, String id, String topic, String question) {
    return InterviewQuestionDTO.createMain(
        index, question, topic.toUpperCase(), topic, topic + " 概览", 3,
        List.of(topic + " 要点"))
        .withQuestionId(id);
  }

  private static InterviewQuestionDTO followUp(
      int index,
      String id,
      String topic,
      String parentId
  ) {
    return InterviewQuestionDTO.createFollowUp(
        index, "追问 " + id, topic.toUpperCase(), topic, parentId, 3,
        InterviewQuestionDTO.FOLLOW_UP_CLARIFICATION, List.of(topic + " 追问要点"))
        .withQuestionId(id);
  }

  private static InterviewTurnDTO answered(InterviewQuestionDTO question, int ordinal) {
    return new InterviewTurnDTO(
        question.questionId(), ordinal, question.questionIndex(), question.question(),
        question.category(), question.topic(), "有效回答", InterviewAnswerEntity.AnswerState.ANSWERED,
        null, null, InterviewTurnDTO.ACTION_NEXT_MAIN, null, List.of(), null);
  }

  private static EvaluationReport.QuestionEvaluation evaluated(
      int index,
      InterviewQuestionDTO question,
      int score
  ) {
    return new EvaluationReport.QuestionEvaluation(
        index, question.question(), question.category(), "有效回答", score, "正式反馈");
  }

  private static EvaluationReport semanticReport(
      String sessionId,
      List<EvaluationReport.QuestionEvaluation> evaluations
  ) {
    return new EvaluationReport(
        sessionId, evaluations.size(), null, List.of(), evaluations,
        "总体反馈", List.of(), List.of(), List.of());
  }

  private EvaluationReport report() {
    return new EvaluationReport(
        "session1",
        1,
        80,
        List.of(new EvaluationReport.CategoryScore("MySQL", 80, 1)),
        List.of(new EvaluationReport.QuestionEvaluation(
            0,
            "什么是索引下推？",
            "MySQL",
            "回答",
            80,
            "不错"
        )),
        "整体不错",
        List.of("基础扎实"),
        List.of("继续深入"),
        List.of(new EvaluationReport.ReferenceAnswer(
            0,
            "什么是索引下推？",
            "模型参考",
            List.of("模型要点")
        ))
    );
  }
}
