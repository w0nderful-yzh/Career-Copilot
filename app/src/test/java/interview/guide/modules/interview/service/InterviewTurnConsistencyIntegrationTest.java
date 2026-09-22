package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewTurnCommit;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.interview.model.InterviewTurnResult;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
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
 * 逐轮提交一致性的真实持久化验证（P4-1 / P4-9a）。
 *
 * <p>模拟测试只能证明「决定是对的」，证明不了**数据库上的并发闸门与原子写**：
 * 条件更新影响 0 行是否真的拒绝、一次推进是否只留一行答案、结束原因与评估请求是否同批落地。
 * 本类直连本地 dev 库，在真实 JPA + 迁移表结构上验证这些。
 *
 * <p>P4-1 起闸门与定位都用**题目标识**（不是数组下标）：因此断言里出现的是
 * `currentQuestionId` / `questionId`，下标只作为展示顺序被顺带校验。
 *
 * <p>数据自播种（会话 id 带 {@code e2e-turn-} 前缀），由测试事务回滚清理；
 * 环境不可达时整类跳过（见 {@link LocalDatabaseGate}）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
@EnabledIf(value = "interview.guide.support.LocalDatabaseGate#available",
    disabledReason = "本地 dev 数据库不可达（POSTGRES_* 未配置或端口未监听），跳过真实 DB 集成验证")
@DisplayName("逐轮提交一致性集成验证（P4-1 / P4-9a）")
class InterviewTurnConsistencyIntegrationTest {

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

  /** 模拟消费端完成评估：真实链路里由评估 Stream 消费端写状态 */
  @Autowired
  private InterviewSessionRepository sessionRepository;

  private static List<InterviewQuestionDTO> questions() {
    return List.of(
        InterviewQuestionDTO.createMain(0, "E2E Q1", "JAVA", "Java", null, 3, List.of()),
        InterviewQuestionDTO.createMain(1, "E2E Q2", "REDIS", "Redis", null, 3, List.of()));
  }

  /** 播种一个进行中的会话（0 号题为待答题，当前题标识已写入） */
  private List<InterviewQuestionDTO> seedSession(String sessionId) {
    persistenceService.saveSession(sessionId, null, 2, questions(), "dashscope", "java-backend",
        "mid");
    return candidatesOf(sessionId);
  }

  private List<InterviewQuestionDTO> candidatesOf(String sessionId) {
    List<InterviewQuestionDTO> candidates = persistenceService.findCandidatesBySessionId(sessionId);
    assertThat(candidates).hasSize(2);
    return candidates;
  }

  /** 一轮作答的提交命令（P4-1：闸门与答案都用题目标识） */
  private static InterviewTurnCommit answerCommit(String sessionId, String requestId,
                                                  String expectedQuestionId, int questionIndex,
                                                  boolean completing, String newQuestionId,
                                                  int newIndex) {
    return answerCommit(sessionId, requestId, expectedQuestionId, questionIndex, completing,
        newQuestionId, newIndex, 0);
  }

  private static InterviewTurnCommit answerCommit(String sessionId, String requestId,
                                                  String expectedQuestionId, int questionIndex,
                                                  boolean completing, String newQuestionId,
                                                  int newIndex, int expectedVersion) {
    return InterviewTurnCommit.ofTurn(sessionId, requestId, "ANSWER", "hash", expectedVersion,
        expectedQuestionId, newIndex, newQuestionId, expectedQuestionId, 120,
        completing ? InterviewTurnDTO.ACTION_FINISH_EXHAUSTED : InterviewTurnDTO.ACTION_NEXT_MAIN,
        completing, questionIndex, "E2E Q" + (questionIndex + 1), "Java", "作答内容",
        InterviewAnswerEntity.AnswerState.ANSWERED, "{}");
  }

  @Test
  @DisplayName("重复推进同一版本：只有一次生效，不会留下第二行答案")
  void secondAdvanceWithSameVersionIsRejected() {
    String sessionId = "e2e-turn-dup-0001";
    List<InterviewQuestionDTO> candidates = seedSession(sessionId);
    String first = candidates.get(0).questionId();
    String second = candidates.get(1).questionId();

    InterviewTurnResult result = persistenceService.applyTurn(
        answerCommit(sessionId, null, first, 0, false, second, 1));
    assertThat(result.turnVersion()).isEqualTo(1);
    assertThat(result.turnOrdinal()).as("真实发生顺序由落库分配").isEqualTo(1);

    // 同一请求（同一版本）再来一次：条件更新影响 0 行，被明确拒绝
    assertThatThrownBy(() -> persistenceService.applyTurn(
        answerCommit(sessionId, null, first, 0, false, second, 1)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.INTERVIEW_TURN_STALE.getCode());

    InterviewSessionEntity after = persistenceService.findBySessionId(sessionId).orElseThrow();
    assertThat(after.getTurnVersion()).isEqualTo(1);
    assertThat(after.getCurrentQuestionId()).isEqualTo(second);
    assertThat(after.getCurrentQuestionIndex()).isEqualTo(1);
    assertThat(persistenceService.findAnswersBySessionId(sessionId))
        .as("重复提交只推进一次：答案事实也只能有一行")
        .hasSize(1);
    assertThat(persistenceService.findTurnsBySessionId(sessionId)).hasSize(1);
  }

  @Test
  @DisplayName("不是当前待答题的提交：拒绝，当前题与轨迹都不动")
  void indexMismatchIsRejectedWithoutMovingIndex() {
    String sessionId = "e2e-turn-idx-0002";
    List<InterviewQuestionDTO> candidates = seedSession(sessionId);
    String current = candidates.get(0).questionId();
    String other = candidates.get(1).questionId();

    // 当前待答是第 0 题，却按第 1 题提交（标识与下标都对不上）
    assertThatThrownBy(() -> persistenceService.applyTurn(
        answerCommit(sessionId, null, other, 1, false, null, 2)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.INTERVIEW_TURN_STALE.getCode());

    InterviewSessionEntity after = persistenceService.findBySessionId(sessionId).orElseThrow();
    assertThat(after.getTurnVersion()).isZero();
    assertThat(after.getCurrentQuestionId()).isEqualTo(current);
    assertThat(after.getCurrentQuestionIndex()).isZero();
    assertThat(persistenceService.findAnswersBySessionId(sessionId)).isEmpty();
    assertThat(persistenceService.findTurnsBySessionId(sessionId)).isEmpty();
  }

  @Test
  @DisplayName("答完最后一题：当前题、状态、结束原因、评估请求与代次在同一次提交里落地")
  void lastTurnCommitsEverythingAtomically() {
    String sessionId = "e2e-turn-last-0003";
    List<InterviewQuestionDTO> candidates = seedSession(sessionId);
    String first = candidates.get(0).questionId();
    String second = candidates.get(1).questionId();

    // 先答第 0 题推进到第 1 题
    persistenceService.applyTurn(answerCommit(sessionId, null, first, 0, false, second, 1));

    // 再答最后一题：这一轮同时收束会话并请求评估；候选耗尽 → 没有新的当前题
    InterviewTurnResult result = persistenceService.applyTurn(
        answerCommit(sessionId, "turn-req-last-1", second, 1, true, null, 2, 1));

    assertThat(result.turnVersion()).isEqualTo(2);
    assertThat(result.evaluateEpoch()).isEqualTo(1L);
    assertThat(result.turnOrdinal()).isEqualTo(2);

    InterviewSessionEntity after = persistenceService.findBySessionId(sessionId).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.COMPLETED);
    assertThat(after.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(after.getEvaluateStatusUpdatedAt())
        .as("评估状态时间与结束提交原子落库，刷新后可判定是否卡住")
        .isNotNull();
    assertThat(after.getEvaluateEpoch()).isEqualTo(1L);
    assertThat(after.getEndReason())
        .as("候选耗尽必须与「用户结束」可区分（P4-1）")
        .isEqualTo(InterviewSessionEntity.END_CANDIDATES_EXHAUSTED);
    assertThat(after.getCurrentQuestionId()).as("没有下一题就不再指向任何题目").isNull();
    assertThat(after.getCompletedAt()).as("结束时间与状态同批写入").isNotNull();
    assertThat(after.getTurnVersion()).isEqualTo(2);
    assertThat(persistenceService.findAnswersBySessionId(sessionId)).hasSize(2);
    assertThat(persistenceService.findTurnsBySessionId(sessionId))
        .extracting(InterviewTurnDTO::displayOrdinal)
        .containsExactly(1, 2);
  }

  @Test
  @DisplayName("已结束的会话：晚到的推进被拒，不会被写回进行中")
  void lateTurnCannotReopenCompletedSession() {
    String sessionId = "e2e-turn-finish-0004";
    List<InterviewQuestionDTO> candidates = seedSession(sessionId);
    String first = candidates.get(0).questionId();
    String second = candidates.get(1).questionId();

    persistenceService.applyTurn(InterviewTurnCommit.ofFinish(
        sessionId, null, "hash", 0, first, 0, "{}"));

    // 用户结束之后又来了一个「基于旧版本」的推进：必须被拒
    assertThatThrownBy(() -> persistenceService.applyTurn(
        answerCommit(sessionId, null, first, 0, false, second, 1)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.INTERVIEW_TURN_STALE.getCode());

    InterviewSessionEntity after = persistenceService.findBySessionId(sessionId).orElseThrow();
    assertThat(after.getStatus())
        .as("晚到的结果不得把已结束的会话写回进行中")
        .isEqualTo(InterviewSessionEntity.SessionStatus.COMPLETED);
    assertThat(after.getEndReason()).isEqualTo(InterviewSessionEntity.END_USER_FINISHED);
    assertThat(after.getTurnVersion()).isEqualTo(1);
    assertThat(persistenceService.findTurnsBySessionId(sessionId)).isEmpty();
  }

  @Test
  @DisplayName("评估触发按代次领取：旧代次与已完成都被拒（晚到模型结果不重复计算）")
  void staleEvaluationTriggerIsDiscarded() {
    String sessionId = "e2e-turn-epoch-0005";
    List<InterviewQuestionDTO> candidates = seedSession(sessionId);
    String first = candidates.get(0).questionId();

    // 一轮收束：会话进入 COMPLETED、评估状态 PENDING、代次 +1
    persistenceService.applyTurn(
        answerCommit(sessionId, "req-epoch-1", first, 0, true, null, 2, 0));
    assertThat(persistenceService.findEvaluateEpoch(sessionId)).contains(1L);

    // 当前代次可领取（消费端原子领取）
    assertThat(persistenceService.claimEvaluation(sessionId, 1L)).isTrue();
    assertThat(persistenceService.findBySessionId(sessionId).orElseThrow()
        .getEvaluateStatusUpdatedAt()).isNotNull();
    // 同一代次再次投递（失败重试路径）仍可领取：重试是刻意允许的
    assertThat(persistenceService.claimEvaluation(sessionId, 1L)).isTrue();
    // **旧代次的晚到触发被丢弃**：否则一次重试会写出第二份报告
    assertThat(persistenceService.claimEvaluation(sessionId, 0L)).isFalse();

    // 报告已完成后不再领取：不会重复计算已有结果
    InterviewSessionEntity finished = persistenceService.findBySessionId(sessionId).orElseThrow();
    finished.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    sessionRepository.save(finished);
    assertThat(persistenceService.claimEvaluation(sessionId, 1L)).isFalse();
  }
}
