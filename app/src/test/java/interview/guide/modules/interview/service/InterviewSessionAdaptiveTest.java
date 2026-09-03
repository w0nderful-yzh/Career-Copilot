package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.CreateInterviewRequest;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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

  private static List<InterviewQuestionDTO> linearSession() {
    List<InterviewQuestionDTO> list = new ArrayList<>();
    list.add(InterviewQuestionDTO.createMain(0, "Q1: JVM 内存模型？", "JVM", "JVM", "内存", 3, List.of("堆")));
    list.add(InterviewQuestionDTO.createFollowUp(1, "F1a: 堆区分代？", "JVM", "JVM（追问1）", 0, "DEPTH", List.of("young")));
    list.add(InterviewQuestionDTO.createMain(2, "Q2: Redis 持久化？", "REDIS", "Redis", "持久化", 3, List.of("RDB")));
    list.add(InterviewQuestionDTO.createFollowUp(3, "F2a: AOF 重写？", "REDIS", "Redis（追问1）", 2, "DEPTH", List.of("rewrite")));
    return list;
  }

  private CachedSession cached(List<InterviewQuestionDTO> questions, int index, boolean adaptive) {
    return new CachedSession("session-abc", "", null, null, null,
        questions, index, SessionStatus.IN_PROGRESS, adaptive, objectMapper);
  }

  @Test
  @DisplayName("自适应会话：答不上 → 中断追问组切到下一主问题")
  void adaptiveSessionSkipsFollowUpToNextMain() {
    List<InterviewQuestionDTO> questions = linearSession();
    when(sessionCache.getSession("session-abc")).thenReturn(Optional.of(cached(questions, 0, true)));
    when(persistenceService.findBySessionId("session-abc"))
        .thenReturn(Optional.of(entity("session-abc", true)));
    when(turnEvaluationService.evaluateTurn(any(), any(), any()))
        .thenReturn(TurnEvaluation.noAnswer());

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest("session-abc", 0, "不会"));

    assertThat(response.hasNextQuestion()).isTrue();
    // 决策跳到 Q2（index 2），而不是顺序的 F1a（index 1）
    assertThat(response.nextQuestion().question()).isEqualTo("Q2: Redis 持久化？");
    assertThat(response.currentIndex()).isEqualTo(2);
  }

  @Test
  @DisplayName("自适应会话：答得好 → 进入该主问题的追问池")
  void adaptiveSessionEntersFollowUpOnGoodAnswer() {
    List<InterviewQuestionDTO> questions = linearSession();
    when(sessionCache.getSession("session-abc")).thenReturn(Optional.of(cached(questions, 0, true)));
    when(persistenceService.findBySessionId("session-abc"))
        .thenReturn(Optional.of(entity("session-abc", true)));
    when(turnEvaluationService.evaluateTurn(any(), any(), any()))
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

  private static TurnEvaluation eval(AnswerState state) {
    return new TurnEvaluation(TurnEvaluation.defaultScoreFor(state), 0.5,
        List.of(), List.of(), state, "", true);
  }
}
