package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewTurnCommit;
import interview.guide.modules.interview.model.InterviewTurnRequestEntity;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.interview.model.InterviewTurnResult;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.repository.InterviewTurnRequestRepository;
import interview.guide.modules.profile.service.SkillProfileAggregator;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewPersistenceServiceTest {

  @Mock
  private InterviewSessionRepository sessionRepository;

  @Mock
  private InterviewAnswerRepository answerRepository;

  @Mock
  private InterviewTurnRequestRepository turnRequestRepository;

  @Mock
  private ResumeRepository resumeRepository;

  @Mock
  private SkillProfileAggregator profileAggregator;

  @Mock
  private InterviewSessionCache sessionCache;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("知识库面试保存时写入 interviewCategory")
  void shouldSaveInterviewCategoryForKnowledgeBaseSession() {
    InterviewPersistenceService service = newService();
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.saveSession("sid1", null, 1, List.of(), "dashscope",
        "knowledge-base", "mid", "KNOWLEDGE_BASE", 9L, "MySQL");

    ArgumentCaptor<InterviewSessionEntity> captor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    InterviewSessionEntity saved = captor.getValue();
    assertThat(saved.getInterviewCategory()).isEqualTo("MySQL");
    assertThat(saved.getKnowledgeBaseId()).isEqualTo(9L);
    assertThat(saved.getSourceType()).isEqualTo("KNOWLEDGE_BASE");
  }

  @Test
  @DisplayName("普通面试保存时 interviewCategory 保持 null")
  void shouldKeepInterviewCategoryNullForNormalSession() {
    InterviewPersistenceService service = newService();
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.saveSession("sid2", null, 1, List.of(), "dashscope", "java-backend", "mid");

    ArgumentCaptor<InterviewSessionEntity> captor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    InterviewSessionEntity saved = captor.getValue();
    assertThat(saved.getInterviewCategory()).isNull();
    assertThat(saved.getSourceType()).isEqualTo("NORMAL");
    assertThat(saved.getKnowledgeBaseId()).isNull();
  }

  @Test
  @DisplayName("删除会话时同步失效 Redis 缓存（防幽灵会话残留）")
  void deleteSessionEvictsCache() {
    InterviewPersistenceService service = newService();
    InterviewSessionEntity entity = new InterviewSessionEntity();
    entity.setSessionId("ghost-session");
    when(sessionRepository.findBySessionId("ghost-session")).thenReturn(Optional.of(entity));

    service.deleteSessionBySessionId("ghost-session");

    verify(sessionRepository).delete(entity);
    verify(sessionCache).deleteSession("ghost-session");
  }

  @Test
  @DisplayName("评估完成时同步会话缓存状态，避免前端一直显示「评估中」（P4Q-4）")
  void evaluationCompletedSyncsCachedSessionStatus() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("sid2");
    when(sessionRepository.findBySessionId("sid2")).thenReturn(Optional.of(session));
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    newService().updateEvaluateStatus("sid2", AsyncTaskStatus.COMPLETED, null);

    verify(sessionCache).updateSessionStatus(
        "sid2", InterviewSessionDTO.SessionStatus.EVALUATED);
  }

  @Test
  @DisplayName("评估失败不顺带改会话状态：报告缺失由前端重试，不伪装成已评估")
  void evaluationFailedDoesNotTouchSessionStatus() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("sid3");
    when(sessionRepository.findBySessionId("sid3")).thenReturn(Optional.of(session));
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    newService().updateEvaluateStatus("sid3", AsyncTaskStatus.FAILED, "模型超时");

    verify(sessionCache, never()).updateSessionStatus(any(), any());
    assertThat(session.getEvaluateError()).isEqualTo("模型超时");
  }

  @Test
  @DisplayName("逐轮提交：条件更新没命中就整体不落任何写入（P4-9a）")
  void applyTurnRejectsWhenConditionalUpdateMisses() {
    when(sessionRepository.applyTurn(anyString(), anyInt(), any(), any(), anyInt(), any(), anyInt(), any(), any(), anyList()))
        .thenReturn(0);

    assertThatThrownBy(() -> newService().applyTurn(answerCommit()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.INTERVIEW_TURN_STALE.getCode());

    // 版本过期 / 已结束 / 不是当前待答题：答案与幂等记录都不该出现
    verify(answerRepository, never()).save(any());
    verify(turnRequestRepository, never()).save(any());
  }

  @Test
  @DisplayName("逐轮提交：答案事实与幂等记录跟着同一次条件更新落地（P4-9a）")
  void applyTurnWritesAnswerAndIdempotencyRecord() {
    when(sessionRepository.applyTurn(anyString(), anyInt(), any(), any(), anyInt(), any(), anyInt(), any(), any(), anyList()))
        .thenReturn(1);
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("sid-turn");
    session.setTurnVersion(3);
    session.setEvaluateEpoch(7L);
    when(sessionRepository.findBySessionId("sid-turn")).thenReturn(Optional.of(session));
    when(answerRepository.findBySession_SessionIdAndQuestionId("sid-turn", "q-turn-1"))
        .thenReturn(Optional.empty());
    when(answerRepository.save(any(InterviewAnswerEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    InterviewTurnResult result = newService().applyTurn(answerCommit());

    assertThat(result.turnVersion()).isEqualTo(3);
    assertThat(result.evaluateEpoch()).isEqualTo(7L);
    assertThat(result.turnOrdinal()).as("发生顺序在事务内分配（1 起）").isEqualTo(1);

    ArgumentCaptor<InterviewAnswerEntity> answerCaptor =
        ArgumentCaptor.forClass(InterviewAnswerEntity.class);
    verify(answerRepository).save(answerCaptor.capture());
    assertThat(answerCaptor.getValue().getAnswerState())
        .isEqualTo(InterviewAnswerEntity.AnswerState.ANSWERED);
    assertThat(answerCaptor.getValue().getUserAnswer()).isEqualTo("堆和栈……");
    assertThat(answerCaptor.getValue().getDecidedNextQuestionId()).isEqualTo("q-turn-2");
    assertThat(answerCaptor.getValue().getDecisionReason()).isEqualTo("验证 Redis 持久化");
    assertThat(answerCaptor.getValue().getTransitionMessage()).isEqualTo("下面转到 Redis。");

    ArgumentCaptor<InterviewTurnRequestEntity> recordCaptor =
        ArgumentCaptor.forClass(InterviewTurnRequestEntity.class);
    verify(turnRequestRepository).save(recordCaptor.capture());
    assertThat(recordCaptor.getValue().getRequestId()).isEqualTo("turn-req-0001");
    assertThat(recordCaptor.getValue().getBaseVersion()).isEqualTo(2);
    assertThat(recordCaptor.getValue().getResultVersion()).isEqualTo(3);
  }

  @Test
  @DisplayName("提前交卷走「不推进索引」的条件更新（P4-9a）")
  void finishCommitUsesFinishUpdate() {
    when(sessionRepository.applyFinish(anyString(), anyInt(), any(), any(), any(), anyInt(), any(), anyList()))
        .thenReturn(1);
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("sid-turn");
    session.setTurnVersion(5);
    session.setEvaluateEpoch(2L);
    when(sessionRepository.findBySessionId("sid-turn")).thenReturn(Optional.of(session));

    newService().applyTurn(InterviewTurnCommit.ofFinish(
        "sid-turn", "req-finish-1", "hash", 4, "q-current", 1, "{}"));

    verify(sessionRepository).applyFinish(anyString(), anyInt(), any(), any(), any(), anyInt(), any(), anyList());
    verify(sessionRepository, never())
        .applyTurn(anyString(), anyInt(), any(), any(), anyInt(), any(), anyInt(), any(), any(), anyList());
  }

  /** 一轮作答的提交命令（版本 2 → 3；P4-1：闸门与答案都用题目标识） */
  private static InterviewTurnCommit answerCommit() {
    return InterviewTurnCommit.ofTurn("sid-turn", "turn-req-0001", "ANSWER", "hash", 2,
        "q-turn-1", 2, "q-turn-2", "q-turn-1", 120, InterviewTurnDTO.ACTION_NEXT_MAIN,
        "验证 Redis 持久化", "下面转到 Redis。", false, 1, "Q2", "Redis", "堆和栈……",
        InterviewAnswerEntity.AnswerState.ANSWERED, "{}");
  }

  private InterviewPersistenceService newService() {
    return new InterviewPersistenceService(
        sessionRepository,
        answerRepository,
        turnRequestRepository,
        resumeRepository,
        objectMapper,
        profileAggregator,
        sessionCache
    );
  }
}
