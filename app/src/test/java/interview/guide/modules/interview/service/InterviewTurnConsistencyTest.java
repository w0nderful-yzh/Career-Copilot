package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewResumeContext;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewTurnCommit;
import interview.guide.modules.interview.model.InterviewTurnRequestEntity;
import interview.guide.modules.interview.model.InterviewTurnResult;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
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
 * 逐轮请求幂等与会话推进一致性（P4-9a）。
 *
 * <p>要守住的四件事：
 * <ol>
 *   <li>重复提交只推进一次——同标识重放直接返回原结果，不再落库、也不再花一次模型调用；</li>
 *   <li>同一标识换了载荷、或者提交方拿的是过期版本 → 明确拒绝，而不是静默改写历史；</li>
 *   <li>处理中重复请求不得再次独立推进；</li>
 *   <li>已结束的会话、以及不是当前待答题的提交，都不允许推进（晚到的结果无法回退会话）。</li>
 * </ol>
 */
@DisplayName("逐轮提交幂等与推进一致性（P4-9a）")
@ExtendWith(MockitoExtension.class)
class InterviewTurnConsistencyTest {

  private static final String SESSION = "s1";
  private static final String REQUEST_ID = "turn-req-0001";

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
    // 占位默认成功；「正在处理中」的用例单独覆盖
    lenient().when(redisService.setIfAbsent(anyString(), any(), any())).thenReturn(true);
    lenient().when(persistenceService.applyTurn(any())).thenAnswer(invocation -> {
      committed = invocation.getArgument(0, InterviewTurnCommit.class);
      return new InterviewTurnResult(committed.expectedVersion() + 1, 1L);
    });
  }

  private static List<InterviewQuestionDTO> pool() {
    return List.of(
        InterviewQuestionDTO.createMain(0, "Q1", "JAVA", "Java", null, 3, List.of()),
        InterviewQuestionDTO.createMain(1, "Q2", "REDIS", "Redis", null, 3, List.of()));
  }

  /** 题库 JSON（缓存自愈路径会按它重建问题列表） */
  private static String poolJson() {
    try {
      return new ObjectMapper().writeValueAsString(pool());
    } catch (Exception e) {
      throw new IllegalStateException("构造题库 JSON 失败", e);
    }
  }

  /** 准备一个进行中的会话：缓存副本与数据库实体共用同一索引与版本 */
  private void givenSession(int currentIndex, int turnVersion) {
    cacheStore.put(SESSION, new CachedSession(
        SESSION, "简历", null, null, null, pool(), currentIndex, SessionStatus.IN_PROGRESS, false,
        objectMapper));

    InterviewSessionEntity entity = new InterviewSessionEntity();
    entity.setSessionId(SESSION);
    entity.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
    entity.setCurrentQuestionIndex(currentIndex);
    entity.setTurnVersion(turnVersion);
    entity.setEvaluateEpoch(0L);
    entity.setQuestionsJson(poolJson());
    entityStore.put(SESSION, entity);
  }

  private static InterviewTurnRequestEntity storedRecord(String action, int index, String answer,
                                                         String responseJson) {
    InterviewTurnRequestEntity record = new InterviewTurnRequestEntity();
    record.setSessionId(SESSION);
    record.setRequestId(REQUEST_ID);
    record.setAction(action);
    record.setPayloadHash(InterviewSessionService.turnPayloadHash(action, index, answer));
    record.setBaseVersion(0);
    record.setResultVersion(1);
    record.setResponseJson(responseJson);
    return record;
  }

  // ===== 1. 重复提交只推进一次 =====

  @Test
  @DisplayName("同标识重放：返回原结果，不再次落库也不再花模型调用")
  void replayReturnsStoredResultWithoutAdvancing() throws Exception {
    givenSession(0, 1);
    SubmitAnswerResponse original = new SubmitAnswerResponse(true, null, 1, 2, 1);
    when(persistenceService.findTurnRequest(SESSION, REQUEST_ID)).thenReturn(Optional.of(
        storedRecord("ANSWER", 0, "堆和方法区……", objectMapper.writeValueAsString(original))));

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "堆和方法区……", REQUEST_ID, 0));

    assertThat(response.turnVersion()).isEqualTo(1);
    assertThat(response.currentIndex()).isEqualTo(1);
    verify(persistenceService, never()).applyTurn(any());
    verify(turnEvaluationService, never()).evaluateTurn(any(), any());
  }

  @Test
  @DisplayName("同标识换了载荷：明确拒绝，不把标识当改写历史的开关")
  void sameRequestWithDifferentPayloadIsRejected() throws Exception {
    givenSession(0, 1);
    when(persistenceService.findTurnRequest(SESSION, REQUEST_ID)).thenReturn(Optional.of(
        storedRecord("ANSWER", 0, "原始答案", objectMapper.writeValueAsString(
            new SubmitAnswerResponse(true, null, 1, 2, 1)))));

    assertThatThrownBy(() -> service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "换一个答案试试", REQUEST_ID, 0)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code",
            ErrorCode.INTERVIEW_TURN_REQUEST_CONFLICT.getCode());

    verify(persistenceService, never()).applyTurn(any());
  }

  @Test
  @DisplayName("提交方版本过期：拒绝并先把缓存按数据库重建，再给出可见原因")
  void staleVersionIsRejectedAndCacheSelfHeals() {
    givenSession(0, 3);

    assertThatThrownBy(() -> service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "堆和方法区……", REQUEST_ID, 1)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.INTERVIEW_TURN_STALE.getCode());

    // 自愈：按数据库实体重建缓存（读答案回填）
    verify(persistenceService).findAnswersBySessionId(SESSION);
    verify(persistenceService, never()).applyTurn(any());
  }

  @Test
  @DisplayName("处理中重复请求：不再次独立推进，也不花模型调用")
  void concurrentDuplicateDoesNotAdvanceIndependently() {
    givenSession(0, 1);
    when(redisService.setIfAbsent(anyString(), any(), any())).thenReturn(false);
    when(persistenceService.findTurnRequest(SESSION, REQUEST_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "堆和方法区……", REQUEST_ID, 0)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code",
            ErrorCode.INTERVIEW_TURN_IN_PROGRESS.getCode());

    verify(persistenceService, never()).applyTurn(any());
    verify(turnEvaluationService, never()).evaluateTurn(any(), any());
  }

  @Test
  @DisplayName("处理中占位的临界情况：恰好落库完成（占位未释放）→ 按重放返回原结果")
  void duplicateThatJustFinishedIsReplayed() throws Exception {
    givenSession(0, 0);
    when(redisService.setIfAbsent(anyString(), any(), any())).thenReturn(false);
    // 第一次查询（占位之前）还没看到记录，第二次（占位失败后复核）已经落库
    when(persistenceService.findTurnRequest(SESSION, REQUEST_ID))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(storedRecord("ANSWER", 0, "堆和方法区……",
            objectMapper.writeValueAsString(new SubmitAnswerResponse(false, null, 2, 2, 1)))));

    SubmitAnswerResponse response = service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "堆和方法区……", REQUEST_ID, 0));

    assertThat(response.turnVersion()).isEqualTo(1);
    verify(persistenceService, never()).applyTurn(any());
  }

  // ===== 2. 会话状态与待答题边界 =====

  @Test
  @DisplayName("已结束的会话不接受新提交：晚到的结果不得把会话写回进行中")
  void completedSessionRejectsFurtherTurns() {
    givenSession(2, 5);
    entityStore.get(SESSION).setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);

    assertThatThrownBy(() -> service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 2, "补一句", REQUEST_ID, 5)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.INTERVIEW_ALREADY_COMPLETED.getCode());

    verify(persistenceService, never()).applyTurn(any());
  }

  @Test
  @DisplayName("提交的题号不是当前待答题：拒绝，不让索引回退或重复推进同一题")
  void nonCurrentQuestionIsRejected() {
    givenSession(1, 1);

    assertThatThrownBy(() -> service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "补答第一题", REQUEST_ID, 1)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code",
            ErrorCode.INTERVIEW_TURN_INDEX_MISMATCH.getCode());

    verify(persistenceService, never()).applyTurn(any());
  }

  // ===== 3. 跳过与结束共用同一并发边界 =====

  @Test
  @DisplayName("跳过走同一条推进链路：同一套标识、版本与单事务落库")
  void skipSharesTheSameBoundary() {
    givenSession(0, 0);

    service.skipQuestion(SESSION, 0, REQUEST_ID, 0);

    assertThat(committed.action()).isEqualTo("SKIP");
    assertThat(committed.expectedVersion()).isEqualTo(0);
    assertThat(committed.requestId()).isEqualTo(REQUEST_ID);
  }

  @Test
  @DisplayName("交卷重放：同标识不重复提交，也不会重复入队评估")
  void duplicateCompleteIsIgnored() throws Exception {
    givenSession(1, 1);
    when(persistenceService.findTurnRequest(SESSION, REQUEST_ID)).thenReturn(Optional.of(
        storedRecord("COMPLETE", -1, null, "{}")));

    service.completeInterview(SESSION, REQUEST_ID, 1);

    verify(persistenceService, never()).applyTurn(any());
    verify(evaluateStreamProducer, never()).sendEvaluateTask(anyString(), anyLong());
  }

  @Test
  @DisplayName("交卷成功后：状态与版本跟随提交写缓存，并按代次入队评估")
  void completeCachesAfterCommitAndEnqueues() {
    givenSession(1, 1);

    service.completeInterview(SESSION, REQUEST_ID, 1);

    assertThat(committed.action()).isEqualTo("COMPLETE");
    assertThat(committed.finishing())
        .as("交卷必须走「不推进索引、只收束会话」的分支，否则会把已结束的会话写回进行中")
        .isTrue();
    assertThat(committed.completing()).isTrue();
    verify(sessionCache).updateSessionStatus(SESSION, SessionStatus.COMPLETED);
    verify(sessionCache).updateTurnVersion(SESSION, 2);
    verify(evaluateStreamProducer).sendEvaluateTask(eq(SESSION), anyLong());
  }

  @Test
  @DisplayName("提交标识格式不合法：直接拒绝，不把它当成幂等键使用")
  void invalidRequestIdIsRejected() {
    givenSession(0, 0);

    assertThatThrownBy(() -> service.submitAnswer(
        new SubmitAnswerRequest(SESSION, 0, "堆和方法区……", "短", 0)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.BAD_REQUEST.getCode());
  }
}
