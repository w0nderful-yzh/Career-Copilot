package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewAnswerEntity.AnswerState;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.model.TurnEvaluation;
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
import interview.guide.modules.interview.model.InterviewResumeContext;

/**
 * 跳过与答案语义分离（P4Q-5）。
 *
 * <p>核心要求：跳过是**一等动作**，与「答错」严格区分——不调模型、不追问、不计分、
 * 不产生画像证据；未作答与「明确不会」同样不参与技术评分。
 *
 * <p>缺陷期间的现场（dev 库真实数据）：用户输入「跳过」被当成技术答案打 0 分，
 * 再以 0 分进入画像证据；「这题我明确我会，打字的话太多了，跳过」同类。
 */
@DisplayName("跳过与答案语义分离")
@ExtendWith(MockitoExtension.class)
class SkipSemanticsTest {

  /** 逐题评估的状态枚举（与实体 AnswerState 同名，故用外层类限定避免歧义） */
  private static final TurnEvaluation.AnswerState TURN_GOOD = TurnEvaluation.AnswerState.GOOD;

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

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Map<String, CachedSession> cacheStore = new HashMap<>();

  private InterviewSessionService service;

  @BeforeEach
  void setUp() {
    service = new InterviewSessionService(
        questionService, evaluationService, persistenceService, sessionCache,
        objectMapper, evaluateStreamProducer, llmProviderRegistry, redisService,
        turnEvaluationService,
        resumeContextResolver);

    lenient().when(resumeContextResolver.resolve(any(), any(), any()))
        .thenReturn(InterviewResumeContext.none());

    lenient().when(sessionCache.getSession(anyString())).thenAnswer(
        invocation -> Optional.ofNullable(cacheStore.get(invocation.getArgument(0, String.class))));
  }

  /** 题单顺序：0 主问题 / 1 其候选追问 / 2 第二个主问题 */
  private static List<InterviewQuestionDTO> pool() {
    return List.of(
        InterviewQuestionDTO.createMain(0, "Q1: JVM 内存模型？", "JAVA", "Java", null, 3, List.of()),
        InterviewQuestionDTO.createFollowUp(1, "F1: 堆为什么分代？", "JAVA", "Java", 0, 1, "WHY",
            List.of()),
        InterviewQuestionDTO.createMain(2, "Q2: Redis 持久化？", "REDIS", "Redis", null, 3,
            List.of()));
  }

  private void putSession(String sessionId, boolean adaptive) {
    cacheStore.put(sessionId, new CachedSession(
        sessionId, "简历", null, null, null, pool(), 0, SessionStatus.IN_PROGRESS, adaptive,
        objectMapper));
  }

  @Test
  @DisplayName("跳过不调模型，并中断追问组切下一主问题")
  void skipDoesNotCallModelAndSkipsFollowUpGroup() {
    putSession("s1", true);

    SubmitAnswerResponse response = service.skipQuestion("s1", 0);

    assertThat(response.hasNextQuestion()).isTrue();
    assertThat(response.nextQuestion().questionIndex()).isEqualTo(2);
    verify(turnEvaluationService, never()).evaluateTurn(any(), any(), any());
  }

  @Test
  @DisplayName("跳过落在追问上时同样中断追问组")
  void skipOnFollowUpMovesToNextMainQuestion() {
    putSession("s2", true);

    SubmitAnswerResponse response = service.skipQuestion("s2", 1);

    assertThat(response.nextQuestion().questionIndex()).isEqualTo(2);
  }

  @Test
  @DisplayName("空白答案记为未作答，同样不调模型")
  void blankAnswerDoesNotCallModel() {
    putSession("s3", true);

    service.submitAnswer(new SubmitAnswerRequest("s3", 0, "   "));

    verify(turnEvaluationService, never()).evaluateTurn(any(), any(), any());
  }

  @Test
  @DisplayName("非自适应会话也识别精确匹配的「跳过」：不调模型、线性推进")
  void nonAdaptiveSessionRecognizesExactSkipPhrase() {
    putSession("s4", false);

    SubmitAnswerResponse response = service.submitAnswer(new SubmitAnswerRequest("s4", 0, "跳过"));

    assertThat(response.nextQuestion().questionIndex()).isEqualTo(1);
    verify(turnEvaluationService, never()).evaluateTurn(any(), any(), any());
  }

  @Test
  @DisplayName("语义判定要求跳过时改判 SKIPPED 且不写分数")
  void semanticSkipIsPersistedWithoutScore() {
    putSession("s5", true);
    when(persistenceService.findBySessionId("s5")).thenReturn(Optional.empty());
    when(turnEvaluationService.evaluateTurn(any(), any(), any()))
        .thenReturn(TurnEvaluation.skipped());

    service.submitAnswer(new SubmitAnswerRequest(
        "s5", 0, "这题我明确我会，打字的话太多了，跳过"));

    // 跳过与 NO_ANSWER 同属「不深挖」：不追问、直接换主问题
    verify(persistenceService).saveAnswer(
        eq("s5"), eq(0), anyString(), anyString(), anyString(), isNull(), isNull(),
        eq(AnswerState.SKIPPED));
  }

  @Test
  @DisplayName("明确不会记为 DECLINED，不写分数")
  void noAnswerBecomesDeclined() {
    putSession("s6", true);
    when(persistenceService.findBySessionId("s6")).thenReturn(Optional.empty());
    when(turnEvaluationService.evaluateTurn(any(), any(), any()))
        .thenReturn(TurnEvaluation.noAnswer());

    service.submitAnswer(new SubmitAnswerRequest("s6", 0, "这个原理我确实没搞明白"));

    verify(persistenceService).saveAnswer(
        eq("s6"), eq(0), anyString(), anyString(), anyString(), isNull(), isNull(),
        eq(AnswerState.DECLINED));
  }

  @Test
  @DisplayName("真实作答照常评估，并按 ANSWERED 预留分数由报告回填")
  void realAnswerIsEvaluatedAndScored() {
    putSession("s7", true);
    when(persistenceService.findBySessionId("s7")).thenReturn(Optional.empty());
    when(turnEvaluationService.evaluateTurn(any(), any(), any()))
        .thenReturn(new TurnEvaluation(80, 0.8, List.of("堆"), List.of(), TURN_GOOD, "", true));

    service.submitAnswer(new SubmitAnswerRequest("s7", 0, "堆和方法区……"));

    verify(turnEvaluationService).evaluateTurn(any(), any(), any());
    verify(persistenceService).saveAnswer(
        eq("s7"), eq(0), anyString(), anyString(), anyString(), eq(0), isNull(),
        eq(AnswerState.ANSWERED));
  }

  @Test
  @DisplayName("跳过后题目可还原「发生过」：刷新不会丢掉已跳过的轮次")
  void skippedQuestionIsRecoverable() {
    InterviewQuestionDTO skipped = pool().get(0).withAnswer(null)
        .withAnswerState(AnswerState.SKIPPED);
    InterviewQuestionDTO untouched = pool().get(2);

    assertThat(skipped.wasAsked()).isTrue();
    assertThat(skipped.answerState()).isEqualTo(AnswerState.SKIPPED);
    assertThat(untouched.wasAsked()).as("候选择问/未走到不算发生过").isFalse();
  }

  @Test
  @DisplayName("只有真实作答计入评分与画像证据")
  void onlyAnsweredCounts() {
    assertThat(answerWith(AnswerState.ANSWERED).countsAsAnswer()).isTrue();
    assertThat(answerWith(AnswerState.SKIPPED).countsAsAnswer()).isFalse();
    assertThat(answerWith(AnswerState.DECLINED).countsAsAnswer()).isFalse();
    assertThat(answerWith(AnswerState.UNANSWERED).countsAsAnswer()).isFalse();
  }

  private static InterviewAnswerEntity answerWith(AnswerState state) {
    InterviewAnswerEntity entity = new InterviewAnswerEntity();
    entity.setAnswerState(state);
    return entity;
  }

  @Test
  @DisplayName("跳过最后一题仍照常入队评估（报告需要说明跳过）")
  void skipOnLastQuestionEnqueuesEvaluation() {
    cacheStore.put("s8", new CachedSession(
        "s8", "简历", null, null, null,
        List.of(InterviewQuestionDTO.createMain(0, "Q1", "JAVA", "Java", null, 3, List.of())),
        0, SessionStatus.IN_PROGRESS, true, objectMapper));

    SubmitAnswerResponse response = service.skipQuestion("s8", 0);

    assertThat(response.hasNextQuestion()).isFalse();
    verify(persistenceService).updateEvaluateStatus("s8", AsyncTaskStatus.PENDING, null);
    verify(evaluateStreamProducer).sendEvaluateTask("s8");
  }

  @Test
  @DisplayName("写回缓存题目列表时也带状态：否则刷新恢复看不到「已跳过」")
  void cachedQuestionsCarryAnswerState() {
    putSession("s10", true);

    service.skipQuestion("s10", 0);

    @SuppressWarnings("unchecked")
    org.mockito.ArgumentCaptor<List<InterviewQuestionDTO>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(sessionCache).updateQuestions(eq("s10"), captor.capture());
    assertThat(captor.getValue().get(0).answerState())
        .as("缓存是进行中会话的读取来源，状态漏写会让跳过的轮次在刷新后消失")
        .isEqualTo(AnswerState.SKIPPED);
    assertThat(captor.getValue().get(1).answerState()).isNull();
  }

  @Test
  @DisplayName("落库失败以业务异常外抛，不静默吞掉")
  void persistenceFailureIsSurfaced() {
    putSession("s9", true);
    doThrow(new RuntimeException("db down")).when(persistenceService)
        .saveAnswer(anyString(), anyInt(), anyString(), anyString(), any(), any(), any(), any());

    assertThatThrownBy(() -> service.skipQuestion("s9", 0))
        .isInstanceOf(BusinessException.class);
  }
}
