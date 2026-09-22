package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewTurnCommit;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.profile.dto.SkillProfileImpactResponse;
import interview.guide.modules.profile.model.EvidenceSourceType;
import interview.guide.modules.profile.model.SkillEvidenceEntity;
import interview.guide.modules.profile.service.InterviewEvidenceExtractor;
import interview.guide.modules.profile.service.SkillProfileImpactService;
import interview.guide.support.LocalDatabaseGate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 画像驱动的面试闭环可追溯（P5-2）。
 *
 * <p>要证的不是「某个接口能返回数据」，而是**整条链路每一环都留得下证据**：
 * 画像重点 → 定向计划（focus + 必要覆盖）→ 实际轮次 → 报告 → 画像证据 → 本场差分。
 * 断任何一环，用户问「这场为什么重点考了它、它对画像做了什么」都答不上来。
 *
 * <p>真实链路里报告由评估消费端产出、证据随后由同一提取器落库；这里手工构造等价载荷
 * 并显式调用同一个提取器，从而在**不依赖模型输出质量**的前提下验证数据链路本身。
 *
 * <p>种子技能名带 e2e 前缀：本类直连共享 dev 库，用真实技能名会与开发者自己的证据混在一起。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
@EnabledIf(value = "interview.guide.support.LocalDatabaseGate#available",
    disabledReason = "本地 dev 数据库不可达（POSTGRES_* 未配置或端口未监听），跳过真实 DB 集成验证")
@DisplayName("画像驱动的面试闭环（P5-2）")
class ProfileDrivenInterviewLoopIntegrationTest {

  private static final String SESSION_ID = "e2e-loop-probe-01";

  /** 定向探针技能：与真实技能隔离，避免污染/被污染 */
  private static final String FOCUS_SKILL = "e2e-focus-probe";

  private static final String OTHER_SKILL = "e2e-other-probe";

  @DynamicPropertySource
  static void contextProperties(DynamicPropertyRegistry registry) {
    if (LocalDatabaseGate.DOTENV != null) {
      LocalDatabaseGate.DOTENV.forEach((key, value) -> registry.add(key, () -> value));
    }
    if (LocalDatabaseGate.DATASOURCE != null) {
      registry.add("spring.datasource.url", LocalDatabaseGate::url);
      registry.add("spring.datasource.username", LocalDatabaseGate::username);
      registry.add("spring.datasource.password", LocalDatabaseGate::password);
    }
  }

  @Autowired
  private InterviewPersistenceService persistenceService;

  @Autowired
  private InterviewEvidenceExtractor evidenceExtractor;

  @Autowired
  private SkillProfileImpactService impactService;

  /** 证据落库：真实链路由评估消费端调用同一个提取器并保存，这里照做 */
  @Autowired
  private interview.guide.modules.profile.repository.SkillEvidenceRepository evidenceRepository;

  private static InterviewQuestionDTO main(String id, String topic, int index) {
    return InterviewQuestionDTO.createMain(index, topic + " 的题目", "JAVA", topic, null, 3,
        List.of("要点")).withQuestionId(id).withFocus(topic, topic);
  }

  private static InterviewTurnCommit turn(int expectedVersion, String expectedId, String questionId,
                                          String newId, int newIndex, boolean completing) {
    return InterviewTurnCommit.ofTurn(SESSION_ID, null, "ANSWER", "hash", expectedVersion,
        expectedId, newIndex,
        newId, questionId, 0,
        completing ? InterviewTurnDTO.ACTION_FINISH_EXHAUSTED : InterviewTurnDTO.ACTION_NEXT_MAIN,
        completing, newIndex == 0 ? 0 : 1, "题目", FOCUS_SKILL, "作答内容",
        InterviewAnswerEntity.AnswerState.ANSWERED, "{}");
  }

  @Test
  @DisplayName("画像重点 → 定向计划 → 轮次 → 报告 → 证据 → 差分，每一环都可追溯")
  void profileDrivenLoopLeavesTraceableEvidence() {
    // 1. 计划来自「画像低分项一键定向」：focus 与必要覆盖都是同一个探针技能
    InterviewPlan plan = InterviewPlan.of(20, List.of(FOCUS_SKILL), List.of(FOCUS_SKILL), null);
    persistenceService.saveSession(SESSION_ID, null, 2,
        List.of(main("q1", FOCUS_SKILL, 0), main("q2", OTHER_SKILL, 1)),
        "dashscope", "java-backend", "mid", true, null, plan);

    InterviewSessionEntity seeded = persistenceService.findBySessionId(SESSION_ID).orElseThrow();
    // 计划快照落库：事后方能回答「这场为什么重点考了它」
    assertThat(seeded.getFocusCategoriesJson()).contains(FOCUS_SKILL);
    assertThat(seeded.getRequiredTopicsJson()).contains(FOCUS_SKILL);
    assertThat(seeded.getPlannedDurationMinutes()).isEqualTo(20);

    // 2. 两轮推进并收束（applyTurn 不含模型调用，链路与模型质量解耦）
    persistenceService.applyTurn(turn(0, "q1", "q1", "q2", 1, false));
    persistenceService.applyTurn(turn(1, "q2", "q2", null, 2, true));

    List<InterviewTurnDTO> turns = persistenceService.findTurnsBySessionId(SESSION_ID);
    assertThat(turns).extracting(InterviewTurnDTO::questionId).containsExactly("q1", "q2");
    assertThat(persistenceService.findBySessionId(SESSION_ID).orElseThrow().getEndReason())
        .isEqualTo(InterviewSessionEntity.END_CANDIDATES_EXHAUSTED);

    // 3. 报告（真实链路由评估消费端产出；这里给等价载荷：两轮都有分，重点话题已考察）
    persistenceService.saveReport(SESSION_ID, report());

    // 4. 证据：消费端在真实链路里调用同一个提取器；来源必须指向**本场这一轮**
    InterviewSessionEntity completed = persistenceService.findBySessionId(SESSION_ID).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.EVALUATED);
    // 诊断：报告回填是否给两轮都写了分（抽取器只取有分的真实作答）
    assertThat(persistenceService.findTurnsBySessionId(SESSION_ID))
        .extracting(InterviewTurnDTO::questionId, InterviewTurnDTO::ordinal, InterviewTurnDTO::score)
        .as("报告回填后每轮都应带上分数")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("q1", 1, 78),
            org.assertj.core.groups.Tuple.tuple("q2", 2, 70));
    List<SkillEvidenceEntity> evidences = evidenceExtractor.extract(SESSION_ID);
    assertThat(evidences)
        .extracting(SkillEvidenceEntity::getSourceType)
        .containsOnly(EvidenceSourceType.INTERVIEW_TURN);
    assertThat(evidences)
        .extracting(SkillEvidenceEntity::getSourceId)
        .containsExactly(SESSION_ID + ":q1", SESSION_ID + ":q2");

    // 5. 证据落库（消费端在真实链路里保存同一个提取器的产出）→ 差分可读
    evidenceRepository.saveAll(evidences);

    // 6. 差分：本场对画像的影响（首次考到 → 没有假涨幅）
    SkillProfileImpactResponse impact = impactService.impactOf(SESSION_ID);
    assertThat(impact.skills()).isNotEmpty();
    assertThat(impact.skills().getFirst().beforeScore())
        .as("本场首次考到该技能：没有历史基线就不该有「涨幅」")
        .isNull();
    // 本场两条证据（78 / 70）进入聚合 → 均值 74，与画像聚合口径一致
    assertThat(impact.skills().getFirst().skill()).isEqualTo(FOCUS_SKILL);
    assertThat(impact.skills().getFirst().afterScore()).isEqualTo(74);
  }

  /** 等价报告载荷：两轮各带分，覆盖里标明重点话题已考察 */
  private static InterviewReportDTO report() {
    InterviewReportDTO.QuestionEvaluation first = new InterviewReportDTO.QuestionEvaluation(
        0, "q1", 1, FOCUS_SKILL + " 的题目", FOCUS_SKILL, FOCUS_SKILL, false, null, 3,
        List.of("要点"), "作答内容", "ANSWERED", 78, "答得不错", "FOLLOW_UP", "深挖", null);
    InterviewReportDTO.QuestionEvaluation second = new InterviewReportDTO.QuestionEvaluation(
        1, "q2", 2, OTHER_SKILL + " 的题目", OTHER_SKILL, OTHER_SKILL, false, null, 3,
        List.of("要点"), "作答内容", "ANSWERED", 70, "基本正确", "FINISH_EXHAUSTED", "候选耗尽", null);
    InterviewReportDTO.TopicCoverage coverage = new InterviewReportDTO.TopicCoverage(
        FOCUS_SKILL, true, "ASSESSED", 1, 1, 0, 1, List.of("q1"), List.of("要点"));
    return new InterviewReportDTO(
        SESSION_ID, 2, 75,
        List.of(new InterviewReportDTO.CategoryScore(FOCUS_SKILL, 78, 1, 1, 1, "单题均值")),
        List.of(first, second), "整体稳定", List.of("基础扎实"), List.of("补充细节"),
        List.of(), "adaptive-report-v1", "按主问题组等权聚合",
        List.of(coverage), List.of(), List.of(), List.of());
  }
}
