package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.model.TurnEvaluation;
import interview.guide.modules.interview.model.TurnEvaluation.AnswerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import interview.guide.modules.interview.model.InterviewResumeContext;
import static org.mockito.Mockito.lenient;

/**
 * P4-3 自适应面试接线：submitAnswer 在 adaptive 会话下调用逐题评估并按决策选题；
 * 非自适应会话保持原「顺序下一题」。
 */
@DisplayName("自适应面试会话答题链路（P4-3）")
@ExtendWith(MockitoExtension.class)
class InterviewSessionAdaptiveTest {

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
  /** P4Q-1：简历上下文解析器（本类不验证取数，stub 为「无简历」以隔离关注点） */
  @Mock
  private InterviewResumeContextResolver resumeContextResolver;
  @Mock
  private interview.guide.modules.profile.service.SkillProfileQueryService skillProfileQueryService;

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
        turnEvaluationService,
        resumeContextResolver,
        skillProfileQueryService
    );

    lenient().when(resumeContextResolver.resolve(any(), any(), any()))
        .thenReturn(InterviewResumeContext.none());
  }

  private static List<InterviewQuestionDTO> linearSession() {
    List<InterviewQuestionDTO> list = new ArrayList<>();
    list.add(InterviewQuestionDTO.createMain(0, "Q1: JVM 内存模型？", "JVM", "JVM", "内存", 3, List.of("堆")));
    list.add(InterviewQuestionDTO.createFollowUp(1, "F1a: 堆区分代？", "JVM", "JVM", 0, 1, "DEPTH", List.of("young")));
    list.add(InterviewQuestionDTO.createMain(2, "Q2: Redis 持久化？", "REDIS", "Redis", "持久化", 3, List.of("RDB")));
    list.add(InterviewQuestionDTO.createFollowUp(3, "F2a: AOF 重写？", "REDIS", "Redis", 2, 1, "DEPTH", List.of("rewrite")));
    return list;
  }

  private CachedSession cached(List<InterviewQuestionDTO> questions, int index, boolean adaptive) {
    return new CachedSession("session-abc", "", null, null, null,
        questions, index, SessionStatus.IN_PROGRESS, adaptive, objectMapper);
  }

  @Test
  @DisplayName("自适应会话：答不上 → 中断追问组切到下一主问题（且不花模型调用）")
  void adaptiveSessionSkipsFollowUpToNextMain() {
    List<InterviewQuestionDTO> questions = linearSession();
    when(sessionCache.getSession("session-abc")).thenReturn(Optional.of(cached(questions, 0, true)));

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest("session-abc", 0, "不会"));

    assertThat(response.hasNextQuestion()).isTrue();
    // 决策跳到 Q2（index 2），而不是顺序的 F1a（index 1）
    assertThat(response.nextQuestion().question()).isEqualTo("Q2: Redis 持久化？");
    assertThat(response.currentIndex()).isEqualTo(2);
    // 「不会」是精确匹配的「明确不会」：P4Q-5 起在调用模型前就短路，省掉一次无意义的模型调用
    verify(turnEvaluationService, never()).evaluateTurn(any(), any());
  }

  @Test
  @DisplayName("自适应会话：答得好 → 进入该主问题的追问池")
  void adaptiveSessionEntersFollowUpOnGoodAnswer() {
    List<InterviewQuestionDTO> questions = linearSession();
    when(sessionCache.getSession("session-abc")).thenReturn(Optional.of(cached(questions, 0, true)));
    when(persistenceService.findBySessionId("session-abc"))
        .thenReturn(Optional.of(entity("session-abc", true)));
    when(turnEvaluationService.evaluateTurn(any(), any()))
        .thenReturn(eval(AnswerState.GOOD));

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest("session-abc", 0, "堆/栈……"));

    assertThat(response.hasNextQuestion()).isTrue();
    assertThat(response.nextQuestion().question()).isEqualTo("F1a: 堆区分代？");
  }

  @Test
  @DisplayName("非自适应会话：无论评估结果都按顺序推进下一题")
  void nonAdaptiveSessionKeepsSequentialOrder() {
    List<InterviewQuestionDTO> questions = linearSession();
    when(sessionCache.getSession("session-abc")).thenReturn(Optional.of(cached(questions, 0, false)));

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest("session-abc", 0, "不会"));

    // 顺序下一题 = F1a（追问也按顺序问），即使回答是「不会」
    assertThat(response.hasNextQuestion()).isTrue();
    assertThat(response.nextQuestion().question()).isEqualTo("F1a: 堆区分代？");
  }

  private static InterviewSessionEntity entity(String sessionId, boolean adaptive) {
    InterviewSessionEntity e = new InterviewSessionEntity();
    e.setSessionId(sessionId);
    e.setAdaptive(adaptive);
    e.setLlmProvider("glm");
    return e;
  }

  @ParameterizedTest
  @MethodSource("unavailableEvaluations")
  @DisplayName("评估不可用仍保存真实答案，直接换主问题且不写占位评分")
  void unavailableEvaluationPreservesAnswerWithoutScore(TurnEvaluation evaluation) {
    when(sessionCache.getSession("session-abc"))
        .thenReturn(Optional.of(cached(linearSession(), 0, true)));
    when(turnEvaluationService.evaluateTurn(any(), any())).thenReturn(evaluation);

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest("session-abc", 0, "堆和栈的区别是……"));

    assertThat(response.nextQuestion().questionIndex()).isEqualTo(2);
    assertThat(response.nextQuestion().isFollowUp()).isFalse();
    verify(persistenceService).saveAnswer(eq("session-abc"), eq(0), anyString(), anyString(),
        eq("堆和栈的区别是……"), isNull(), isNull(), eq(InterviewAnswerEntity.AnswerState.ANSWERED));
    verify(persistenceService).updateCurrentQuestionIndex("session-abc", 2);
    verify(evaluateStreamProducer, never()).sendEvaluateTask(anyString());
  }

  private static Stream<TurnEvaluation> unavailableEvaluations() {
    return Stream.of(null, TurnEvaluation.unknownFallback());
  }

  @ParameterizedTest
  @MethodSource("unavailableEvaluations")
  @DisplayName("末个主问题评估不可用时不追问，保留答案并照常进入异步报告")
  void unavailableLastEvaluationStillCompletesInterview(TurnEvaluation evaluation) {
    when(sessionCache.getSession("session-abc"))
        .thenReturn(Optional.of(cached(linearSession(), 2, true)));
    when(turnEvaluationService.evaluateTurn(any(), any())).thenReturn(evaluation);

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest("session-abc", 2, "Redis 支持 RDB 与 AOF……"));

    assertThat(response.hasNextQuestion()).isFalse();
    assertThat(response.nextQuestion()).isNull();
    verify(persistenceService).saveAnswer(eq("session-abc"), eq(2), anyString(), anyString(),
        eq("Redis 支持 RDB 与 AOF……"), isNull(), isNull(), eq(InterviewAnswerEntity.AnswerState.ANSWERED));
    verify(persistenceService).updateSessionStatus("session-abc", InterviewSessionEntity.SessionStatus.COMPLETED);
    verify(persistenceService).updateEvaluateStatus("session-abc", AsyncTaskStatus.PENDING, null);
    verify(evaluateStreamProducer).sendEvaluateTask("session-abc");
  }

  private static TurnEvaluation eval(AnswerState state) {
    return new TurnEvaluation(TurnEvaluation.defaultScoreFor(state), 0.5,
        List.of(), List.of(), state, "", true);
  }
}
