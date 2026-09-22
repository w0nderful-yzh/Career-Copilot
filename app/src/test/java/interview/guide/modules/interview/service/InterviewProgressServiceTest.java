package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewAnswerEntity.AnswerState;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewProgressDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 面试进展读取（P4-10）。
 *
 * <p>守两件事：Agent 拿到的是**与 Java 判据同源**的进展（覆盖/预算/合法候选 + 实际轨迹），
 * 以及这件事**只读**——问一句不该改变面试状态。
 */
@DisplayName("面试进展读取（P4-10）")
class InterviewProgressServiceTest {

  private final InterviewSessionService sessionService = mock(InterviewSessionService.class);
  private final InterviewQuestionService questionService = mock(InterviewQuestionService.class);

  private InterviewProgressService service() {
    when(questionService.getFollowUpBudget()).thenReturn(2);
    return new InterviewProgressService(sessionService, questionService);
  }

  private static InterviewQuestionDTO main(String id, String topic, int index) {
    return InterviewQuestionDTO.createMain(index, topic + " 的主问题", "JAVA", topic, null, 3,
        List.of("要点A")).withQuestionId(id).withFocus(topic, topic);
  }

  private static InterviewQuestionDTO followUp(String id, String parentId, String topic, int index) {
    return InterviewQuestionDTO.createFollowUp(index, topic + " 的追问", "JAVA", topic, parentId, 1,
        "DEPTH", List.of()).withQuestionId(id).withFocus(topic, topic);
  }

  private static InterviewTurnDTO answered(String questionId, int ordinal, String question,
                                           String answer) {
    return new InterviewTurnDTO(questionId, ordinal, ordinal - 1, question, "JVM", "JVM", answer,
        AnswerState.ANSWERED, null, null, InterviewTurnDTO.ACTION_NEXT_MAIN, null, List.of(),
        LocalDateTime.of(2026, 9, 20, 10, ordinal));
  }

  /** 进行中的会话：JVM 已考、数据库正在考、算法还没问；必要覆盖 = [JVM, 数据库] */
  private InterviewSessionDTO inProgress() {
    InterviewQuestionDTO jvm = main("q1", "JVM", 0);
    InterviewQuestionDTO database = main("q3", "数据库", 2);
    InterviewQuestionDTO algorithm = main("q4", "算法", 3);
    List<InterviewQuestionDTO> candidates = List.of(
        jvm,
        followUp("q2", "q1", "JVM", 1),
        database,
        algorithm);
    return new InterviewSessionDTO(
        "s1", "", 4, 2, "q3", database, candidates,
        List.of(answered("q1", 1, "JVM 的主问题", "堆分新生代与老年代")),
        SessionStatus.IN_PROGRESS, null, "java-backend", true, null, null, null, null, 1, null,
        20, 300, 900, List.of("JVM", "数据库"), List.of("JVM"));
  }

  @Test
  @DisplayName("进行中会话：给出当前题、覆盖与预算摘要、已发生轮次与合法候选")
  void returnsLiveProgress() {
    when(sessionService.getSession("s1")).thenReturn(inProgress());

    InterviewProgressDTO progress = service().progressOf("s1", null);

    assertThat(progress.sessionId()).isEqualTo("s1");
    assertThat(progress.status()).isEqualTo("IN_PROGRESS");
    assertThat(progress.currentQuestion()).isNotNull();
    assertThat(progress.currentQuestion().questionId()).isEqualTo("q3");
    assertThat(progress.askedTurnCount()).isEqualTo(1);
    // 已发生内容对 Agent 可见：题面 + 回答 + 本轮决定
    assertThat(progress.turns()).hasSize(1);
    assertThat(progress.turns().getFirst().questionId()).isEqualTo("q1");
    assertThat(progress.turns().getFirst().userAnswer()).contains("新生代");
    assertThat(progress.turns().getFirst().decidedAction()).isEqualTo(InterviewTurnDTO.ACTION_NEXT_MAIN);
    // 覆盖状态与实际轨迹同源：JVM 已覆盖；数据库正是当前这题，标注「本轮正在考察」
    // （它在提交前不算已覆盖，但也不是「没碰过」——Agent 据此才说得清现状）
    assertThat(progress.coverageSummary())
        .contains("JVM=已覆盖")
        .contains("数据库=本轮正在考察");
    assertThat(progress.satisfiedRequiredTopicCount()).isEqualTo(1);
    // 预算与合法候选来自同一套推导（P4Q-2 批 2b）
    assertThat(progress.budgetSummary()).contains("剩余约 15 分钟").contains("追问");
    // 下一步可能被选中的题：只剩未问过的「算法」主问题（当前题与同组追问都不算）
    assertThat(progress.legalCandidates()).hasSize(1);
    assertThat(progress.legalCandidates().getFirst()).contains("算法");
    // 未指定 questionId 时不给单轮详情
    assertThat(progress.turnDetail()).isNull();
    // 只读：不推进、不触发评估
    verify(sessionService, never()).submitAnswer(org.mockito.ArgumentMatchers.any());
    verify(sessionService, never()).updateBudget(anyString(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  @DisplayName("指定题目标识：给这一轮的完整详情，列表里的回答仍被裁剪")
  void returnsTurnDetailOnDemand() {
    String longAnswer = "很长的回答".repeat(100);
    InterviewSessionDTO session = new InterviewSessionDTO(
        "s1", "", 1, 0, "q1", main("q1", "JVM", 0),
        List.of(main("q1", "JVM", 0)),
        List.of(answered("q1", 1, "JVM 的主问题", longAnswer)),
        SessionStatus.IN_PROGRESS, null, "java-backend", true, null, null, null, null, 1, null,
        20, 60, 1140, List.of("JVM"), List.of("JVM"));
    when(sessionService.getSession("s1")).thenReturn(session);

    InterviewProgressDTO progress = service().progressOf("s1", "q1");

    assertThat(progress.turnDetail()).isNotNull();
    assertThat(progress.turnDetail().userAnswer()).as("单轮详情给全文").isEqualTo(longAnswer);
    assertThat(progress.turns().getFirst().userAnswer())
        .as("列表里的回答裁剪，避免把整场对话塞进上下文")
        .hasSizeLessThan(longAnswer.length())
        .contains("已截断");
  }

  @Test
  @DisplayName("旧会话（无计划）：覆盖与预算明确标注未记录，不编造数据")
  void legacySessionHasNoFabricatedPlan() {
    InterviewSessionDTO legacy = new InterviewSessionDTO(
        "s1", "", 1, 0, "q1", main("q1", "JVM", 0),
        List.of(main("q1", "JVM", 0)), List.of(),
        SessionStatus.IN_PROGRESS, null, "java-backend", true, null, null, null, null, 1, null,
        null, 0, null, List.of(), List.of());
    when(sessionService.getSession("s1")).thenReturn(legacy);

    InterviewProgressDTO progress = service().progressOf("s1", null);

    assertThat(progress.plannedDurationMinutes()).isNull();
    assertThat(progress.remainingSeconds()).isNull();
    assertThat(progress.coverageSummary()).contains("未记录计划");
    assertThat(progress.satisfiedRequiredTopicCount()).isZero();
  }

  @Test
  @DisplayName("会话不存在：明确报「会话不存在」，不返回空壳进展")
  void missingSessionIsRejected() {
    when(sessionService.getSession("nope")).thenReturn(null);

    assertThatThrownBy(() -> service().progressOf("nope", null))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.INTERVIEW_SESSION_NOT_FOUND.getCode());
  }
}
