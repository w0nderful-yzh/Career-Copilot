package interview.guide.modules.profile.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewAnswerEntity.AnswerState;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.TurnEvaluation;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.service.TurnEvaluationService;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.profile.repository.SkillEvidenceRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 历史「跳过被当成答案计分」的修复（P4Q-5）。
 *
 * <p>缺陷期间没有跳过动作也没有状态字段，用户用自然语言说「跳过」会被当成技术答案由报告打
 * 0 分，再以 0 分进入画像证据（dev 库里真实发生过：Redis=0、项目经历=0）。
 *
 * <p>修复分两步，都是幂等的：
 * <ol>
 *   <li><b>归类</b>：只挑「已标记作答但得 0 分」的答案做候选——真正答错的答案也在其中，
 *       但它们会被判为非跳过而保持原状，所以候选集合小且安全；
 *       判据与运行期完全一致（先精确匹配短路，再走 {@link TurnEvaluationService} 的语义判断），
 *       保证「修复口径 = 今后行为口径」。
 *   <li><b>作废证据</b>：非作答状态的轮次不构成评分证据，删除对应证据并重算受影响技能。
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileSkipRepairService {

  private static final String DEFAULT_USER_ID = ProfileConstants.DEFAULT_USER_ID;

  private final InterviewAnswerRepository answerRepository;
  private final SkillEvidenceRepository evidenceRepository;
  private final SkillProfileAggregator aggregator;
  private final TurnEvaluationService turnEvaluationService;
  private final LlmProviderRegistry llmProviderRegistry;

  /**
   * 修复历史跳过 / 明确不会被当成答案计分的数据。
   *
   * <p>**刻意不加 {@code @Transactional}**：归类要走模型调用，而外部调用绝不能包在数据库
   * 事务里（事务会被模型延迟长时间占住）。逐条落库由 Repository 自身的短事务承担，
   * 证据清理在独立事务方法里完成。
   *
   * @return 归类与证据清理的实际结果，供调用方核对
   */
  public SkipRepairReport repairHistoricalSkipSemantics() {
    List<InterviewAnswerEntity> candidates = answerRepository
        .findByAnswerStateAndScore(AnswerState.ANSWERED, 0);

    int skipped = 0;
    int declined = 0;
    ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(null);
    for (InterviewAnswerEntity answer : candidates) {
      AnswerState state = classify(chatClient, answer);
      if (state == AnswerState.ANSWERED) {
        continue;
      }
      answer.setAnswerState(state);
      // 非作答轮次不保留分数：0 分会被读成「答错」
      answer.setScore(null);
      answerRepository.save(answer);
      if (state == AnswerState.SKIPPED) {
        skipped++;
      } else {
        declined++;
      }
    }

    List<SkillEvidenceEntity> voided = new ArrayList<>();
    if (skipped + declined > 0) {
      voided = removeEvidenceForNonAnsweredTurns();
    }

    SkipRepairReport report = new SkipRepairReport(
        candidates.size(), skipped, declined, voided.size(),
        voided.stream().map(SkillEvidenceEntity::getSkill).distinct().toList());
    log.info("历史跳过语义修复完成: {}", report);
    return report;
  }

  /**
   * 删除「非真实作答」轮次对应的画像证据并重算。
   *
   * <p>可由调用方单独触发（幂等）：只处理状态不是 ANSWERED 的答案。
   */
  @Transactional(rollbackFor = Exception.class)
  public List<SkillEvidenceEntity> removeEvidenceForNonAnsweredTurns() {
    List<InterviewAnswerEntity> nonAnswered =
        answerRepository.findByAnswerStateNot(AnswerState.ANSWERED);
    if (nonAnswered.isEmpty()) {
      return List.of();
    }

    List<SkillEvidenceEntity> voided = new ArrayList<>();
    for (InterviewAnswerEntity answer : nonAnswered) {
      if (answer.getSession() == null) {
        continue;
      }
      String skill = SkillNameNormalizer.normalize(answer.getCategory());
      if (skill == null || skill.isBlank()) {
        continue;
      }
      String sourceId = answer.getSession().getSessionId() + ":" + answer.getQuestionIndex();
      evidenceRepository
          .findByUserIdAndSkillAndSourceTypeAndSourceId(
              DEFAULT_USER_ID, skill,
              interview.guide.modules.profile.model.EvidenceSourceType.INTERVIEW_TURN, sourceId)
          .ifPresent(voided::add);
    }
    if (voided.isEmpty()) {
      return List.of();
    }

    Set<String> affectedSkills = new LinkedHashSet<>();
    voided.forEach(evidence -> affectedSkills.add(evidence.getSkill()));
    evidenceRepository.deleteAll(voided);
    evidenceRepository.flush();
    // 技能可能因此再无证据，聚合器会顺手删除其画像行
    affectedSkills.forEach(aggregator::reaggregateSkill);
    log.info("非作答轮次证据已作废: 条数={}, 技能={}", voided.size(), affectedSkills);
    return voided;
  }

  /** 与运行期同一判据：先精确匹配短路，再交给逐题评估的语义判断 */
  private AnswerState classify(ChatClient chatClient, InterviewAnswerEntity answer) {
    String text = answer.getUserAnswer();
    TurnEvaluation shortCircuit = TurnEvaluationService.shortCircuit(text);
    TurnEvaluation evaluation = shortCircuit != null
        ? shortCircuit
        : turnEvaluationService.evaluateTurn(chatClient, questionOf(answer), text);

    if (evaluation.skipRequested()) {
      return AnswerState.SKIPPED;
    }
    if (evaluation.answerState() == TurnEvaluation.AnswerState.NO_ANSWER) {
      return AnswerState.DECLINED;
    }
    return AnswerState.ANSWERED;
  }

  /** 历史答案不带期望要点，只用题干与技能名做语义判断（评估器会按常识补足） */
  private static InterviewQuestionDTO questionOf(InterviewAnswerEntity answer) {
    return InterviewQuestionDTO.createMain(
        answer.getQuestionIndex(),
        answer.getQuestion() == null ? "" : answer.getQuestion(),
        answer.getCategory(), answer.getCategory(), null, null, List.of());
  }

  /**
   * 修复结果。
   *
   * @param candidates       被归类审视的答案条数（已标记作答且得 0 分）
   * @param markedSkipped    改判为跳过的条数
   * @param markedDeclined   改判为「明确不会」的条数
   * @param voidedEvidences  被作废的画像证据条数
   * @param affectedSkills   受影响的技能（已重算画像）
   */
  public record SkipRepairReport(
      int candidates,
      int markedSkipped,
      int markedDeclined,
      int voidedEvidences,
      List<String> affectedSkills
  ) {}
}
