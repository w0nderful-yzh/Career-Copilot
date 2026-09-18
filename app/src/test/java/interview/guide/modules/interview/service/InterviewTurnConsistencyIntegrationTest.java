package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewTurnCommit;
import interview.guide.modules.interview.model.InterviewTurnResult;
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
 * 逐轮提交一致性的真实持久化验证（P4-9a）。
 *
 * <p>模拟测试只能证明「决定是对的」，证明不了**数据库上的并发闸门与原子写**：
 * 条件更新影响 0 行是否真的拒绝、一次推进是否只留一行答案、结束与评估请求是否同一次落地。
 * 本类直连本地 dev 库，在真实 JPA + 迁移表结构上验证这些。
 *
 * <p>数据自播种（会话 id 带 {@code e2e-turn-} 前缀，避免与真实数据混淆），
 * 并由测试事务回滚清理。环境不可达时整类跳过（见 {@link LocalDatabaseGate} 的说明）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
@EnabledIf(value = "interview.guide.support.LocalDatabaseGate#available",
    disabledReason = "本地 dev 数据库不可达（POSTGRES_* 未配置或端口未监听），跳过真实 DB 集成验证")
@DisplayName("逐轮提交一致性集成验证（P4-9a）")
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

  private static List<InterviewQuestionDTO> questions() {
    return List.of(
        InterviewQuestionDTO.createMain(0, "E2E Q1", "JAVA", "Java", null, 3, List.of()),
        InterviewQuestionDTO.createMain(1, "E2E Q2", "REDIS", "Redis", null, 3, List.of()));
  }

  /** 播种一个进行中的会话（0 号题为待答题） */
  private void seedSession(String sessionId) {
    persistenceService.saveSession(sessionId, null, 2, questions(), "dashscope", "java-backend",
        "mid");
  }

  private static InterviewTurnCommit answerCommit(String sessionId, String requestId, int index,
                                                  int newIndex) {
    return InterviewTurnCommit.ofTurn(sessionId, requestId, "ANSWER", "hash", 0, index, newIndex,
        false, index, "E2E Q" + (index + 1), "Java", "作答内容",
        interview.guide.modules.interview.model.InterviewAnswerEntity.AnswerState.ANSWERED, "{}");
  }

  @Test
  @DisplayName("重复推进同一版本：只有一次生效，不会留下第二行答案")
  void secondAdvanceWithSameVersionIsRejected() {
    String sessionId = "e2e-turn-dup-0001";
    seedSession(sessionId);

    InterviewTurnResult first = persistenceService.applyTurn(
        answerCommit(sessionId, null, 0, 1));
    assertThat(first.turnVersion()).isEqualTo(1);

    // 同一请求（同一版本）再来一次：条件更新影响 0 行，被明确拒绝
    assertThatThrownBy(() -> persistenceService.applyTurn(answerCommit(sessionId, null, 0, 1)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.INTERVIEW_TURN_STALE.getCode());

    InterviewSessionEntity after = persistenceService.findBySessionId(sessionId).orElseThrow();
    assertThat(after.getTurnVersion()).isEqualTo(1);
    assertThat(after.getCurrentQuestionIndex()).isEqualTo(1);
    assertThat(persistenceService.findAnswersBySessionId(sessionId))
        .as("重复提交只推进一次：答案事实也只能有一行")
        .hasSize(1);
  }

  @Test
  @DisplayName("不是当前待答题的提交：拒绝，索引不会回退")
  void indexMismatchIsRejectedWithoutMovingIndex() {
    String sessionId = "e2e-turn-idx-0002";
    seedSession(sessionId);

    // 待答题是 0，却提交 1
    assertThatThrownBy(() -> persistenceService.applyTurn(answerCommit(sessionId, null, 1, 2)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.INTERVIEW_TURN_STALE.getCode());

    InterviewSessionEntity after = persistenceService.findBySessionId(sessionId).orElseThrow();
    assertThat(after.getTurnVersion()).isZero();
    assertThat(after.getCurrentQuestionIndex()).isZero();
    assertThat(persistenceService.findAnswersBySessionId(sessionId)).isEmpty();
  }

  @Test
  @DisplayName("答完最后一题：索引、状态、评估请求与代次在同一次提交里落地")
  void lastTurnCommitsEverythingAtomically() {
    String sessionId = "e2e-turn-last-0003";
    seedSession(sessionId);

    // 先答第 0 题推进到第 1 题
    persistenceService.applyTurn(answerCommit(sessionId, null, 0, 1));

    // 再答最后一题：这一轮同时收束会话并请求评估
    InterviewTurnResult result = persistenceService.applyTurn(InterviewTurnCommit.ofTurn(
        sessionId, "turn-req-last-1", "ANSWER", "hash", 1, 1, 2, true, 1, "E2E Q2", "Redis",
        "最后一题作答", interview.guide.modules.interview.model.InterviewAnswerEntity.AnswerState.ANSWERED,
        "{\"hasNextQuestion\":false}"));

    assertThat(result.turnVersion()).isEqualTo(2);
    assertThat(result.evaluateEpoch()).isEqualTo(1L);

    InterviewSessionEntity after = persistenceService.findBySessionId(sessionId).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.COMPLETED);
    assertThat(after.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(after.getEvaluateEpoch()).isEqualTo(1L);
    assertThat(after.getCurrentQuestionIndex()).isEqualTo(2);
    assertThat(after.getCompletedAt()).as("结束时间与状态同批写入").isNotNull();
    assertThat(after.getTurnVersion()).isEqualTo(2);
    assertThat(persistenceService.findAnswersBySessionId(sessionId)).hasSize(2);
  }

  @Test
  @DisplayName("已结束的会话：晚到的推进被拒，不会被写回进行中")
  void lateTurnCannotReopenCompletedSession() {
    String sessionId = "e2e-turn-finish-0004";
    seedSession(sessionId);

    persistenceService.applyTurn(InterviewTurnCommit.ofFinish(
        sessionId, null, "hash", 0, 0, "{}"));

    // 用户结束之后又来了一个「基于旧版本」的推进：必须被拒
    assertThatThrownBy(() -> persistenceService.applyTurn(answerCommit(sessionId, null, 0, 1)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.INTERVIEW_TURN_STALE.getCode());

    InterviewSessionEntity after = persistenceService.findBySessionId(sessionId).orElseThrow();
    assertThat(after.getStatus())
        .as("晚到的结果不得把已结束的会话写回进行中")
        .isEqualTo(InterviewSessionEntity.SessionStatus.COMPLETED);
    assertThat(after.getTurnVersion()).isEqualTo(1);
  }
}
