package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewTurnCommit;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.interview.model.InterviewTurnResult;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.model.TurnEvaluation;
import interview.guide.modules.interview.model.TurnEvaluation.AnswerState;
import interview.guide.modules.interview.model.TurnEvaluation.DifficultyAdjust;
import interview.guide.modules.interview.model.TurnEvaluation.RecommendedAction;
import interview.guide.modules.interview.model.TurnEvaluationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import interview.guide.modules.interview.model.InterviewResumeContext;

/**
 * P4-3 自适应面试接线：submitAnswer 在 adaptive 会话下调用逐题评估并按决策选题；
 * 非自适应会话保持原「顺序下一题」。
 *
 * <p>P4-9a 起逐轮推进以**数据库**为权威（缓存只是可恢复副本），并且整轮决定
 * （答案 / 索引 / 状态）在同一个事务里提交，因此本类断言的对象是
 * {@link InterviewTurnCommit}（提交命令）而不是三次分散的写库调用。
 */
@DisplayName("自适应面试会话答题链路（P4-3）")
@ExtendWith(MockitoExtension.class)
class InterviewSessionAdaptiveTest {

  private static final String SESSION = "session-abc";

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
  /** 唯一一次落库的提交命令（P4-9a 的断言对象） */
  private InterviewTurnCommit committed;

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
    // 「这个请求正在处理中」占位默认成功；并发重复请求的场景由 P4-9a 专项用例覆盖
    lenient().when(redisService.setIfAbsent(anyString(), any(), any())).thenReturn(true);
    // 落库默认成功，并记录提交内容供断言
    lenient().when(persistenceService.applyTurn(any())).thenAnswer(invocation -> {
      committed = invocation.getArgument(0, InterviewTurnCommit.class);
      return new InterviewTurnResult(committed.expectedVersion() + 1, 1L);
    });
  }

  private static List<InterviewQuestionDTO> linearSession() {
    // P4-1：追问的父链是父主问题的**稳定标识**
    InterviewQuestionDTO first = InterviewQuestionDTO.createMain(
        0, "Q1: JVM 内存模型？", "JVM", "JVM", "内存", 3, List.of("堆")).withQuestionId("q-a1");
    InterviewQuestionDTO second = InterviewQuestionDTO.createMain(
        2, "Q2: Redis 持久化？", "REDIS", "Redis", "持久化", 3, List.of("RDB"))
        .withQuestionId("q-a2");
    List<InterviewQuestionDTO> list = new ArrayList<>();
    list.add(first);
    list.add(InterviewQuestionDTO.createFollowUp(1, "F1a: 堆区分代？", "JVM", "JVM", "q-a1", 1,
        "DEPTH", List.of("young")).withQuestionId("q-a3"));
    list.add(second);
    list.add(InterviewQuestionDTO.createFollowUp(3, "F2a: AOF 重写？", "REDIS", "Redis", "q-a2", 1,
        "DEPTH", List.of("rewrite")).withQuestionId("q-a4"));
    return list;
  }

  private CachedSession cached(List<InterviewQuestionDTO> questions, int index, boolean adaptive) {
    CachedSession cached = new CachedSession(SESSION, "", null, null, null,
        questions, index, SessionStatus.IN_PROGRESS, adaptive, objectMapper);
    cached.setCurrentQuestionId(questions.get(index).questionId());
    if (index > 0) {
      List<InterviewTurnDTO> previousTurns = questions.stream()
          .filter(InterviewQuestionDTO::isMain)
          .filter(question -> question.questionIndex() < index)
          .map(question -> new InterviewTurnDTO(
              question.questionId(), 1, question.questionIndex(), question.question(),
              question.category(), question.topic(), "已回答", InterviewAnswerEntity.AnswerState.ANSWERED,
              null, null, InterviewTurnDTO.ACTION_NEXT_MAIN, null, List.of(), null))
          .toList();
      try {
        cached.setTurnsJson(objectMapper.writeValueAsString(previousTurns));
      } catch (JacksonException e) {
        throw new AssertionError(e);
      }
    }
    return cached;
  }

  /**
   * 会话的权威实体（数据库那份）：待答题索引与缓存一致，否则提交会被判成「不是当前待答题」
   */
  private static InterviewSessionEntity authoritative(List<InterviewQuestionDTO> questions,
                                                       int index) {
    InterviewSessionEntity entity = new InterviewSessionEntity();
    entity.setSessionId(SESSION);
    entity.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
    entity.setCurrentQuestionIndex(index);
    entity.setCurrentQuestionId(questions.get(index).questionId());
    entity.setTurnVersion(0);
    entity.setEvaluateEpoch(0L);
    entity.setLlmProvider("glm");
    return entity;
  }

  /** 一次会话的准备：缓存副本 + 数据库权威实体（索引一致） */
  private void givenSession(List<InterviewQuestionDTO> questions, int index, boolean adaptive) {
    when(sessionCache.getSession(SESSION)).thenReturn(Optional.of(cached(questions, index, adaptive)));
    when(persistenceService.findBySessionId(SESSION))
        .thenReturn(Optional.of(authoritative(questions, index)));
  }

  @Test
  @DisplayName("自适应会话：答不上 → 中断追问组切到下一主问题（且不花模型调用）")
  void adaptiveSessionSkipsFollowUpToNextMain() {
    List<InterviewQuestionDTO> questions = linearSession();
    givenSession(questions, 0, true);

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "不会"));

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
    givenSession(questions, 0, true);
    when(turnEvaluationService.evaluateTurn(any(), any()))
        .thenReturn(eval(AnswerState.GOOD));

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "堆/栈……"));

    assertThat(response.hasNextQuestion()).isTrue();
    assertThat(response.nextQuestion().question()).isEqualTo("F1a: 堆区分代？");
  }

  @Test
  @DisplayName("合法语义建议的候选、依据与承接语进入响应和持久化事实")
  void acceptedSemanticDecisionIsPersisted() {
    List<InterviewQuestionDTO> questions = linearSession();
    givenSession(questions, 0, true);
    when(turnEvaluationService.evaluateTurn(any(), any())).thenReturn(new TurnEvaluation(
        75, 0.5, List.of("堆"), List.of("分代"), AnswerState.GOOD, "验证分代",
        true, false, RecommendedAction.FOLLOW_UP, "q-a3",
        "分代机制仍需验证", "我们继续看一下分代机制。"));

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "堆和栈……"));

    assertThat(response.nextQuestion().questionId()).isEqualTo("q-a3");
    assertThat(response.transitionMessage()).isEqualTo("我们继续看一下分代机制。");
    assertThat(committed.newQuestionId()).isEqualTo("q-a3");
    assertThat(committed.decisionReason()).isEqualTo("分代机制仍需验证");
    assertThat(committed.transitionMessage()).isEqualTo("我们继续看一下分代机制。");
  }

  @Test
  @DisplayName("无合适候选时受限生成：生成题追加进候选池并随提交一并落库（P4-4b）")
  void restrictedGenerationIsAppendedToCandidatesAndPersisted() {
    List<InterviewQuestionDTO> questions = linearSession();
    givenSession(questions, 0, true);
    when(turnEvaluationService.evaluateTurn(any(), any())).thenReturn(new TurnEvaluation(
        60, 0.5, List.of("堆"), List.of("乱序回填"), AnswerState.PARTIAL, "验证乱序回填",
        true, false, RecommendedAction.FOLLOW_UP_GENERATED, "", "缺少亲历细节", "我们顺着你的回答问一句",
        "你提到回填乱序，当时是怎么定位到这个顺序问题的？", "定位过程", "回填乱序",
        DifficultyAdjust.NONE, false));

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "缓存有个回填乱序的问题……"));

    // 生成题成为正式下一题，带来源标记
    assertThat(response.hasNextQuestion()).isTrue();
    assertThat(response.nextQuestion().isFollowUp()).isTrue();
    assertThat(response.nextQuestion().candidateSource())
        .isEqualTo(InterviewQuestionDTO.CANDIDATE_SOURCE_MODEL_GENERATED);
    assertThat(response.nextQuestion().question()).contains("定位");
    // 提交命令携带追加后的候选池与新代次，生成题挂到当前主问题组
    assertThat(committed.newQuestionsJson()).isNotNull();
    assertThat(committed.generatedQuestionId())
        .isEqualTo(response.nextQuestion().questionId());
    assertThat(committed.newCandidateVersion()).isEqualTo(1);
    assertThat(committed.newQuestionsJson()).contains("回填乱序");
    assertThat(committed.decidedAction()).isEqualTo(InterviewTurnDTO.ACTION_FOLLOW_UP);
  }

  @Test
  @DisplayName("逐轮决策上下文接入覆盖摘要、剩余预算与合法候选（P4Q-2 批 2b）")
  void turnContextCarriesCoverageBudgetAndLegalCandidates() {
    List<InterviewQuestionDTO> questions = linearSession();
    when(sessionCache.getSession(SESSION)).thenReturn(Optional.of(cached(questions, 0, true)));
    // 计划与时间记账放在数据库权威实体上：覆盖与预算都应由真实数据推导
    InterviewSessionEntity entity = authoritative(questions, 0);
    entity.setPlannedDurationMinutes(20);
    entity.setConsumedSeconds(300);
    entity.setQuestionPresentedAt(LocalDateTime.now().minusSeconds(60));
    entity.setRequiredTopicsJson("[\"JVM\"]");
    when(persistenceService.findBySessionId(SESSION)).thenReturn(Optional.of(entity));
    when(turnEvaluationService.evaluateTurn(any(), any())).thenReturn(eval(AnswerState.GOOD));

    service.submitAnswer(new SubmitAnswerRequest(SESSION, 0, "堆/栈……"));

    ArgumentCaptor<TurnEvaluationRequest> request =
        ArgumentCaptor.forClass(TurnEvaluationRequest.class);
    verify(turnEvaluationService).evaluateTurn(any(), request.capture());
    TurnEvaluationRequest context = request.getValue();
    assertThat(context.coverageSummary())
        .contains("JVM=本轮正在考察")
        .contains("尚未问的话题：Redis");
    assertThat(context.budgetSummary()).contains("剩余约");
    assertThat(context.legalCandidates())
        // 本组剩余追问与未问主问题可问；当前题与其他组的预置追问不在合法候选里
        .anyMatch(line -> line.startsWith("[q-a3]"))
        .anyMatch(line -> line.startsWith("[q-a2]"))
        .noneMatch(line -> line.contains("[q-a1]"))
        .noneMatch(line -> line.contains("[q-a4]"));
    assertThat(committed.newQuestionId())
        .as("必要覆盖已完成但仍有关键缺口时，不应机械提前收束")
        .isEqualTo("q-a3");
  }

  @Test
  @DisplayName("覆盖完成且模型确认无关键缺口时按真实原因收束")
  void semanticFinishRequiresCompletedCoverage() {
    List<InterviewQuestionDTO> questions = linearSession();
    when(sessionCache.getSession(SESSION)).thenReturn(Optional.of(cached(questions, 0, true)));
    InterviewSessionEntity entity = authoritative(questions, 0);
    entity.setPlannedDurationMinutes(20);
    entity.setRequiredTopicsJson("[\"JVM\"]");
    when(persistenceService.findBySessionId(SESSION)).thenReturn(Optional.of(entity));
    when(turnEvaluationService.evaluateTurn(any(), any())).thenReturn(new TurnEvaluation(
        92, 1.0, List.of("堆"), List.of(), AnswerState.EXCELLENT, "",
        true, false, RecommendedAction.FINISH, null,
        "必要范围已经充分验证", "信息已经足够，我们到这里。"));

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "JVM 内存结构包括……"));

    assertThat(response.hasNextQuestion()).isFalse();
    assertThat(response.transitionMessage()).isEqualTo("信息已经足够，我们到这里。");
    assertThat(committed.decidedAction()).isEqualTo(InterviewTurnDTO.ACTION_FINISH_COVERAGE);
    assertThat(committed.decisionReason()).contains("必要覆盖");
    assertThat(committed.transitionMessage()).isEqualTo("信息已经足够，我们到这里。");
  }

  @Test
  @DisplayName("进入必要话题预留窗口后，Java 用未覆盖主问题覆盖可选追问")
  void reservesTimeForPendingRequiredTopic() {
    List<InterviewQuestionDTO> questions = linearSession();
    when(sessionCache.getSession(SESSION)).thenReturn(Optional.of(cached(questions, 0, true)));
    InterviewSessionEntity entity = authoritative(questions, 0);
    entity.setPlannedDurationMinutes(5);
    entity.setConsumedSeconds(61);
    entity.setRequiredTopicsJson("[\"REDIS\"]");
    when(persistenceService.findBySessionId(SESSION)).thenReturn(Optional.of(entity));
    when(turnEvaluationService.evaluateTurn(any(), any())).thenReturn(eval(AnswerState.GOOD));

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "堆和栈……"));

    assertThat(response.nextQuestion().questionId()).isEqualTo("q-a2");
    assertThat(committed.decisionReason()).contains("必要话题预留窗口");
    assertThat(committed.transitionMessage()).isNull();
  }

  @Test
  @DisplayName("非自适应会话：无论评估结果都按顺序推进下一题")
  void nonAdaptiveSessionKeepsSequentialOrder() {
    List<InterviewQuestionDTO> questions = linearSession();
    givenSession(questions, 0, false);

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "不会"));

    // 顺序下一题 = F1a（追问也按顺序问），即使回答是「不会」
    assertThat(response.hasNextQuestion()).isTrue();
    assertThat(response.nextQuestion().question()).isEqualTo("F1a: 堆区分代？");
  }

  @ParameterizedTest
  @MethodSource("unavailableEvaluations")
  @DisplayName("评估不可用仍保存真实答案，直接换主问题且不写占位评分")
  void unavailableEvaluationPreservesAnswerWithoutScore(TurnEvaluation evaluation) {
    givenSession(linearSession(), 0, true);
    when(turnEvaluationService.evaluateTurn(any(), any())).thenReturn(evaluation);

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "堆和栈的区别是……"));

    assertThat(response.nextQuestion().questionIndex()).isEqualTo(2);
    assertThat(response.nextQuestion().isFollowUp()).isFalse();
    assertThat(committed.questionIndex()).isEqualTo(0);
    assertThat(committed.answer()).isEqualTo("堆和栈的区别是……");
    assertThat(committed.answerState()).isEqualTo(InterviewAnswerEntity.AnswerState.ANSWERED);
    assertThat(committed.newIndex()).isEqualTo(2);
    verify(evaluateStreamProducer, never()).sendEvaluateTask(anyString(), anyLong());
  }

  private static Stream<TurnEvaluation> unavailableEvaluations() {
    return Stream.of(null, TurnEvaluation.unknownFallback());
  }

  @ParameterizedTest
  @MethodSource("unavailableEvaluations")
  @DisplayName("末个主问题评估不可用时不追问，保留答案并照常进入异步报告")
  void unavailableLastEvaluationStillCompletesInterview(TurnEvaluation evaluation) {
    givenSession(linearSession(), 2, true);
    when(turnEvaluationService.evaluateTurn(any(), any())).thenReturn(evaluation);

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 2, "Redis 支持 RDB 与 AOF……"));

    assertThat(response.hasNextQuestion()).isFalse();
    assertThat(response.nextQuestion()).isNull();
    assertThat(committed.completing()).isTrue();
    assertThat(committed.answer()).isEqualTo("Redis 支持 RDB 与 AOF……");
    assertThat(committed.answerState()).isEqualTo(InterviewAnswerEntity.AnswerState.ANSWERED);
    verify(evaluateStreamProducer).sendEvaluateTask(eq(SESSION), anyLong());
  }

  private static TurnEvaluation eval(AnswerState state) {
    return new TurnEvaluation(TurnEvaluation.defaultScoreFor(state), 0.5,
        List.of(), List.of("关键缺口"), state, "继续验证关键缺口", true);
  }
}
