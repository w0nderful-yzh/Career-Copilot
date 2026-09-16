package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * 报告评估状态的缓存 / 数据库一致性（P4Q-4）。
 *
 * <p>根因：异步评估链路只写数据库（saveReport → 会话 EVALUATED、evaluateStatus COMPLETED），
 * 缓存里的会话状态停在 submitAnswer 时写入的 COMPLETED。会话缓存 TTL 24 小时，
 * 于是前端会一直显示「评估中」——只在 status 上，评估中与评估失败无法区分。
 *
 * <p>修复的两半：写入侧顺手同步缓存；读取侧在唯一的漂移窗口（缓存为 COMPLETED 时）
 * 回源数据库自愈。这里分别固化，并保证进行中会话的读取路径不额外打库。
 */
@DisplayName("报告评估状态闭环：缓存与数据库一致性")
@ExtendWith(MockitoExtension.class)
class SessionReportStateSyncTest {

  @Mock
  private InterviewQuestionService questionService;
  @Mock
  private AnswerEvaluationService evaluationService;
  @Mock
  private InterviewPersistenceService persistenceService;
  @Mock
  private InterviewSessionCache sessionCache;
  @Mock
  private EvaluateStreamProducer evaluateStreamProducer;
  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private RedisService redisService;
  @Mock
  private TurnEvaluationService turnEvaluationService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private InterviewSessionService service;

  @BeforeEach
  void setUp() {
    service = new InterviewSessionService(
        questionService,
        evaluationService,
        persistenceService,
        sessionCache,
        objectMapper,
        evaluateStreamProducer,
        llmProviderRegistry,
        redisService,
        turnEvaluationService
    );
  }

  private CachedSession cached(String sessionId, SessionStatus status) {
    return new CachedSession(
        sessionId, "简历文本", null, null, null,
        List.of(InterviewQuestionDTO.createMain(0, "Q1", "JAVA", "Java", null, 3, List.of())),
        1, status, true, objectMapper);
  }

  private InterviewSessionEntity entity(String sessionId, InterviewSessionEntity.SessionStatus status,
                                        AsyncTaskStatus evaluateStatus, String error) {
    InterviewSessionEntity entity = new InterviewSessionEntity();
    entity.setSessionId(sessionId);
    entity.setStatus(status);
    entity.setEvaluateStatus(evaluateStatus);
    entity.setEvaluateError(error);
    return entity;
  }

  @Test
  @DisplayName("缓存残留 COMPLETED 时按数据库自愈为 EVALUATED，并回写缓存")
  void selfHealsStaleCompletedCache() {
    CachedSession stale = cached("s1", SessionStatus.COMPLETED);
    when(sessionCache.getSession("s1")).thenReturn(Optional.of(stale));
    when(persistenceService.findBySessionId("s1")).thenReturn(Optional.of(
        entity("s1", InterviewSessionEntity.SessionStatus.EVALUATED,
            AsyncTaskStatus.COMPLETED, null)));

    InterviewSessionDTO dto = service.getSession("s1");

    assertThat(dto.status()).isEqualTo(SessionStatus.EVALUATED);
    assertThat(dto.evaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    verify(sessionCache).updateSessionStatus("s1", SessionStatus.EVALUATED);
  }

  @Test
  @DisplayName("数据库仍在评估时透出 evaluateStatus，前端据此显示「评估中」而不是失败")
  void exposesProcessingStateWhileEvaluating() {
    CachedSession cached = cached("s2", SessionStatus.COMPLETED);
    when(sessionCache.getSession("s2")).thenReturn(Optional.of(cached));
    when(persistenceService.findBySessionId("s2")).thenReturn(Optional.of(
        entity("s2", InterviewSessionEntity.SessionStatus.COMPLETED,
            AsyncTaskStatus.PROCESSING, null)));

    InterviewSessionDTO dto = service.getSession("s2");

    assertThat(dto.status()).isEqualTo(SessionStatus.COMPLETED);
    assertThat(dto.evaluateStatus()).isEqualTo(AsyncTaskStatus.PROCESSING);
    // 数据库状态与缓存一致：不需要回写，避免无意义的缓存写
    verify(sessionCache, never()).updateSessionStatus(anyString(), any());
  }

  @Test
  @DisplayName("评估失败时透出失败原因，前端可据此给重试入口")
  void exposesEvaluationFailure() {
    when(sessionCache.getSession("s3"))
        .thenReturn(Optional.of(cached("s3", SessionStatus.COMPLETED)));
    when(persistenceService.findBySessionId("s3")).thenReturn(Optional.of(
        entity("s3", InterviewSessionEntity.SessionStatus.COMPLETED,
            AsyncTaskStatus.FAILED, "模型超时")));

    InterviewSessionDTO dto = service.getSession("s3");

    assertThat(dto.evaluateStatus()).isEqualTo(AsyncTaskStatus.FAILED);
    assertThat(dto.evaluateError()).isEqualTo("模型超时");
  }

  @Test
  @DisplayName("进行中的会话纯走缓存，不额外查库（性能护栏）")
  void inProgressSessionDoesNotHitDatabase() {
    when(sessionCache.getSession("s4"))
        .thenReturn(Optional.of(cached("s4", SessionStatus.IN_PROGRESS)));

    InterviewSessionDTO dto = service.getSession("s4");

    assertThat(dto.status()).isEqualTo(SessionStatus.IN_PROGRESS);
    verify(persistenceService, never()).findBySessionId(anyString());
  }

  @Test
  @DisplayName("重试评估：未完成的面试不允许重试")
  void retryRejectedBeforeInterviewCompletes() {
    when(sessionCache.getSession("s5"))
        .thenReturn(Optional.of(cached("s5", SessionStatus.IN_PROGRESS)));

    assertThatThrownBy(() -> service.retryEvaluation("s5"))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.INTERVIEW_NOT_COMPLETED.getCode());
    verify(evaluateStreamProducer, never()).sendEvaluateTask(anyString());
  }

  @Test
  @DisplayName("重试评估：报告已就绪时幂等返回，不重复评分")
  void retryIsIdempotentWhenReportReady() {
    when(sessionCache.getSession("s6"))
        .thenReturn(Optional.of(cached("s6", SessionStatus.EVALUATED)));
    when(persistenceService.findBySessionId("s6")).thenReturn(Optional.of(
        entity("s6", InterviewSessionEntity.SessionStatus.EVALUATED,
            AsyncTaskStatus.COMPLETED, null)));

    InterviewSessionDTO dto = service.retryEvaluation("s6");

    assertThat(dto.status()).isEqualTo(SessionStatus.EVALUATED);
    verify(evaluateStreamProducer, never()).sendEvaluateTask(anyString());
    // 幂等路径不应把评估状态重置回 PENDING
    verify(persistenceService, never()).updateEvaluateStatus(anyString(), any(), any());
  }

  @Test
  @DisplayName("重试评估：失败后重置为 PENDING 并重新入队")
  void retryReenqueuesAfterFailure() {
    when(sessionCache.getSession("s7"))
        .thenReturn(Optional.of(cached("s7", SessionStatus.COMPLETED)));
    when(persistenceService.findBySessionId("s7")).thenReturn(Optional.of(
        entity("s7", InterviewSessionEntity.SessionStatus.COMPLETED,
            AsyncTaskStatus.FAILED, "模型超时")));

    service.retryEvaluation("s7");

    verify(persistenceService).updateEvaluateStatus("s7", AsyncTaskStatus.PENDING, null);
    verify(evaluateStreamProducer).sendEvaluateTask("s7");
  }
}
