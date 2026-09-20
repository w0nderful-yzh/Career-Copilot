package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewResumeContext;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewTurnCommit;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.interview.model.InterviewTurnResult;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.model.TurnEvaluation;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * 计划边界**真的改变行为**（§4.5 契约行为回归）。
 *
 * <p>字段落地不等于业务生效。本类守的是 P4Q-2 两个硬边界在提交路径上确实起作用：
 * 预算用尽收束（`BUDGET_EXHAUSTED`）与必要覆盖完成收束（`COVERAGE_SATISFIED`），
 * 以及旧会话（无计划）不被新边界误伤。断言落在**提交命令**上（决定 + 是否收束 + 新当前题），
 * 因为那才是写进库、复盘时可解释的事实。
 *
 * <p>用顺序会话（非自适应）刻意绕开模型：边界是 Java 判定，不该依赖模型输出。
 */
@DisplayName("计划边界真的改变行为（§4.5）")
@ExtendWith(MockitoExtension.class)
class InterviewPlanBoundaryTest {

  private static final String SESSION = "s-boundary";

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
  @Mock
  private InterviewResumeContextResolver resumeContextResolver;
  @Mock
  private interview.guide.modules.profile.service.SkillProfileQueryService skillProfileQueryService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Map<String, CachedSession> cacheStore = new HashMap<>();
  private final Map<String, InterviewSessionEntity> entityStore = new HashMap<>();

  private InterviewSessionService service;
  /** 最近一次落库的提交命令 */
  private InterviewTurnCommit committed;

  @BeforeEach
  void setUp() {
    service = new InterviewSessionService(
        questionService, evaluationService, persistenceService, sessionCache,
        objectMapper, evaluateStreamProducer, llmProviderRegistry, redisService,
        turnEvaluationService, resumeContextResolver, skillProfileQueryService);

    lenient().when(resumeContextResolver.resolve(any(), any(), any()))
        .thenReturn(InterviewResumeContext.none());
    lenient().when(sessionCache.getSession(anyString())).thenAnswer(
        invocation -> Optional.ofNullable(cacheStore.get(invocation.getArgument(0, String.class))));
    lenient().when(persistenceService.findBySessionId(anyString())).thenAnswer(
        invocation -> Optional.ofNullable(entityStore.get(invocation.getArgument(0, String.class))));
    lenient().when(redisService.setIfAbsent(anyString(), any(), any())).thenReturn(true);
    lenient().when(persistenceService.applyTurn(any())).thenAnswer(invocation -> {
      committed = invocation.getArgument(0, InterviewTurnCommit.class);
      return new InterviewTurnResult(committed.expectedVersion() + 1, 1L, 1);
    });
  }

  /** 两个主问题：q1（Java）、q2（Redis）——顺序会话，不触发模型 */
  private static List<InterviewQuestionDTO> pool() {
    return List.of(
        InterviewQuestionDTO.createMain(0, "JVM 内存模型？", "JAVA", "Java", null, 3, List.of())
            .withQuestionId("q1"),
        InterviewQuestionDTO.createMain(1, "Redis 持久化？", "REDIS", "Redis", null, 3, List.of())
            .withQuestionId("q2"));
  }

  /**
   * 播种进行中的会话。
   *
   * @param plannedMinutes 计划时长；null = 旧会话（无计划，不受新边界约束）
   * @param requiredTopics 必要覆盖话题
   */
  private void givenSession(Integer plannedMinutes, List<String> requiredTopics) {
    CachedSession cached = new CachedSession(SESSION, "简历", null, null, null, pool(), 0,
        SessionStatus.IN_PROGRESS, false, objectMapper);
    cached.setCurrentQuestionId("q1");
    cacheStore.put(SESSION, cached);

    InterviewSessionEntity entity = new InterviewSessionEntity();
    entity.setSessionId(SESSION);
    entity.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
    entity.setCurrentQuestionIndex(0);
    entity.setCurrentQuestionId("q1");
    entity.setTurnVersion(0);
    entity.setEvaluateEpoch(0L);
    entity.setLlmProvider("glm");
    if (plannedMinutes != null) {
      entity.setPlannedDurationMinutes(plannedMinutes);
      entity.setRequiredTopicsJson(requiredTopics.isEmpty() ? "[]" : toJson(requiredTopics));
      entity.setQuestionPresentedAt(LocalDateTime.now().minusSeconds(20));
      entity.setConsumedSeconds(0);
    }
    entityStore.put(SESSION, entity);
  }

  private String toJson(List<String> values) {
    try {
      return objectMapper.writeValueAsString(values);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("预算用尽：不再出下一题，按 BUDGET_EXHAUSTED 收束并记真实决定")
  void budgetExhaustedStopsTheInterview() {
    // 计划 1 分钟（下限 5 分钟会被夹取，所以直接用边界上的计划：5 分钟 + 已用 299 秒）
    givenSession(5, List.of());
    entityStore.get(SESSION).setConsumedSeconds(299);

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, "q1", 0, "堆分新生代与老年代", null, 0));

    assertThat(response.hasNextQuestion())
        .as("预算已到：不能再摆出一道用户没时间答的题")
        .isFalse();
    assertThat(committed.completing()).isTrue();
    assertThat(committed.decidedAction()).isEqualTo(InterviewTurnDTO.ACTION_FINISH_BUDGET);
    assertThat(committed.newQuestionId()).isNull();
    assertThat(committed.answerSeconds()).as("时间记账随本轮提交一起落地").isPositive();
    // 顺序会话不该因为边界而调模型
    verify(turnEvaluationService, never()).evaluateTurn(any(), any());
  }

  @Test
  @DisplayName("必要覆盖完成且模型建议收束：剩下的候选不再继续问，按 COVERAGE_SATISFIED 收束")
  void coverageSatisfiedEndsInterviewBeforeCandidatesRunOut() {
    // 真实语义（P4Q-3b）：收束 = 模型建议结束 + 无关键缺口 + 必要覆盖已达标。
    // 因此这里必须走自适应会话并喂一份「建议收束」的评估——覆盖单独达标不该直接掐断面试。
    givenAdaptiveSession(30, List.of("Java"));
    when(turnEvaluationService.evaluateTurn(any(), any())).thenReturn(finishSuggested());

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, "q1", 0, "堆分新生代与老年代", null, 0));

    assertThat(response.hasNextQuestion()).as("覆盖已达标且模型建议收束：无需陪跑剩余候选").isFalse();
    assertThat(committed.completing()).isTrue();
    assertThat(committed.decidedAction()).isEqualTo(InterviewTurnDTO.ACTION_FINISH_COVERAGE);
  }

  @Test
  @DisplayName("覆盖已达标但模型没建议收束：仍然继续推进（覆盖不单独掐断面试）")
  void coverageAloneDoesNotEndInterview() {
    givenAdaptiveSession(30, List.of("Java"));
    when(turnEvaluationService.evaluateTurn(any(), any())).thenReturn(goodAnswer());

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, "q1", 0, "堆分新生代与老年代", null, 0));

    assertThat(response.hasNextQuestion())
        .as("覆盖不是「问完了」，还有追问/下一个主题可挖时不自动结束")
        .isTrue();
    assertThat(committed.completing()).isFalse();
  }

  /** 自适应会话：会走逐轮评估（因此模型相关分支需要桩） */
  private void givenAdaptiveSession(Integer plannedMinutes, List<String> requiredTopics) {
    givenSession(plannedMinutes, requiredTopics);
    CachedSession cached = cacheStore.get(SESSION);
    cached.setAdaptive(true);
  }

  /** 模型建议收束且没有关键缺口（Java 再校验覆盖后才真收束） */
  private static TurnEvaluation finishSuggested() {
    return new TurnEvaluation(82, 0.9, List.of("分代"), List.of(),
        TurnEvaluation.AnswerState.GOOD, "", true, false,
        TurnEvaluation.RecommendedAction.FINISH, null, "覆盖已充分", "");
  }

  /** 答得不错但不建议收束：应继续挖 */
  private static TurnEvaluation goodAnswer() {
    return new TurnEvaluation(80, 0.6, List.of("分代"), List.of("触发条件"),
        TurnEvaluation.AnswerState.GOOD, "GC 触发条件", true, false,
        TurnEvaluation.RecommendedAction.FOLLOW_UP, "q2", "有缺口可深挖", "");
  }

  @Test
  @DisplayName("必要覆盖未完成：预算充足时继续推进下一题")
  void pendingCoverageKeepsGoing() {
    givenSession(30, List.of("Java", "Redis"));

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, "q1", 0, "堆分新生代与老年代", null, 0));

    assertThat(response.hasNextQuestion()).isTrue();
    assertThat(response.nextQuestion().questionId()).isEqualTo("q2");
    assertThat(committed.decidedAction()).isEqualTo(InterviewTurnDTO.ACTION_NEXT_MAIN);
    assertThat(committed.completing()).isFalse();
  }

  @Test
  @DisplayName("旧会话（无计划）：不被覆盖/预算边界误伤，照旧按候选推进")
  void legacySessionWithoutPlanIsNotConstrained() {
    givenSession(null, List.of());

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, "q1", 0, "堆分新生代与老年代", null, 0));

    assertThat(response.hasNextQuestion()).as("无计划就不该凭空收束").isTrue();
    assertThat(committed.decidedAction()).isEqualTo(InterviewTurnDTO.ACTION_NEXT_MAIN);
  }
}
