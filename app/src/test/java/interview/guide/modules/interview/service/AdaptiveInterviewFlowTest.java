package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewTurnCommit;
import interview.guide.modules.interview.model.InterviewTurnDTO;
import interview.guide.modules.interview.model.InterviewTurnResult;
import interview.guide.modules.interview.model.TurnEvaluation;
import interview.guide.modules.interview.model.TurnEvaluation.AnswerState;
import interview.guide.modules.interview.policy.AdaptiveInterviewPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import interview.guide.modules.interview.model.InterviewResumeContext;

/**
 * 自适应面试端到端流程回归（P4 待修正）。
 *
 * <p>覆盖审计明确要求的四个场景：题目合并、跳过追问、刷新恢复、提前结束。
 *
 * <p>与 {@code InterviewSessionAdaptiveTest}（单点接线）的分工：本类沿「合并后的真实题库」
 * 走完整链路，重点验证**索引偏移不会让追问归属错位**，以及**缓存失效后按 DB 重建时，
 * 被策略跳过的追问不会携带答案**（前端据此只重放真实发生过的轮次）。
 */
@DisplayName("自适应面试端到端流程（合并 / 跳过追问 / 刷新恢复 / 提前结束）")
@ExtendWith(MockitoExtension.class)
class AdaptiveInterviewFlowTest {

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

  /**
   * 内存 store 代替 Redis。
   *
   * <p>刻意不用「依次返回两个 stub」的写法：只有让 saveSession 真的写入、getSession 真的读出，
   * 「DB → 缓存」恢复路径中的答案回填才会被验证，而不是把预期结果直接塞回给被测代码。
   */
  private final Map<String, CachedSession> cacheStore = new HashMap<>();

  private InterviewSessionService service;
  /** 最近一次落库的提交命令（P4-9a） */
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

    // 纯策略用例（合并 / 跳过追问）不经过服务，故与下面的写入桩一同声明为 lenient
    lenient().when(sessionCache.getSession(anyString())).thenAnswer(
        invocation -> Optional.ofNullable(cacheStore.get(invocation.getArgument(0, String.class))));
    // P4-9a：逐轮提交以数据库为权威，并在同一个事务里落库
    lenient().when(persistenceService.applyTurn(any())).thenAnswer(invocation -> {
      committed = invocation.getArgument(0, InterviewTurnCommit.class);
      return new InterviewTurnResult(committed.expectedVersion() + 1, 1L);
    });
    lenient().when(redisService.setIfAbsent(anyString(), any(), any())).thenReturn(true);

    // 只有「DB 恢复」路径会写缓存，其余用例不触发，故声明为 lenient
    lenient().doAnswer(invocation -> {
      String sessionId = invocation.getArgument(0, String.class);
      List<InterviewQuestionDTO> questions = invocation.getArgument(5);
      int currentIndex = invocation.getArgument(6, Integer.class);
      SessionStatus status = invocation.getArgument(7, SessionStatus.class);
      Boolean adaptive = invocation.getArgument(8, Boolean.class);
      cacheStore.put(sessionId, new CachedSession(
          sessionId, "", null, null, null, questions, currentIndex, status, adaptive, objectMapper));
      return null;
    }).when(sessionCache).saveSession(
        anyString(), anyString(), any(), any(), any(), anyList(), anyInt(), any(), anyBoolean());
    lenient().doAnswer(invocation -> {
      String sessionId = invocation.getArgument(0, String.class);
      CachedSession cached = cacheStore.get(sessionId);
      if (cached != null) {
        cached.setTurnsJson(objectMapper.writeValueAsString(invocation.getArgument(2)));
        cached.setCurrentIndex(invocation.getArgument(3, Integer.class));
        cached.setCurrentQuestionId(invocation.getArgument(4, String.class));
        cached.setStatus(invocation.getArgument(5, SessionStatus.class));
        cached.setTurnVersion(invocation.getArgument(6, Integer.class));
      }
      return null;
    }).when(sessionCache).applyTurnState(
        anyString(), anyList(), anyList(), anyInt(), any(), any(), any(), anyInt());
  }

  /** 进行中的会话实体（数据库权威那份） */
  private static InterviewSessionEntity inProgress(String sessionId, int currentIndex) {
    InterviewSessionEntity entity = entity(sessionId, true);
    entity.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
    entity.setCurrentQuestionIndex(currentIndex);
    entity.setTurnVersion(0);
    entity.setEvaluateEpoch(0L);
    return entity;
  }

  // ===== 题库构造 =====

  /** 简历题批次：1 主问题 + 1 候选追问（P4-1：追问的父链是父主问题的标识） */
  private static List<InterviewQuestionDTO> resumeBatch() {
    InterviewQuestionDTO main = InterviewQuestionDTO.createMain(0, "R1: 介绍你最有挑战的项目",
        "PROJECT", "项目", "项目深挖", 3, List.of("背景", "个人贡献")).withQuestionId("q-r1");
    return List.of(
        main,
        InterviewQuestionDTO.createFollowUp(1, "RF1: 你具体负责哪一块？", "PROJECT", "项目",
            "q-r1", 1, InterviewQuestionDTO.FOLLOW_UP_DEPTH, List.of("职责边界"))
            .withQuestionId("q-rf1")
    );
  }

  /** 方向题批次（独立索引：主 0 / 追问 1 / 主 2），合并后只重排展示顺序 */
  private static List<InterviewQuestionDTO> directionBatch() {
    return List.of(
        InterviewQuestionDTO.createMain(0, "Q1: JVM 内存模型？", "JVM", "JVM",
            "运行时数据区", 4, List.of("堆", "栈", "方法区")).withQuestionId("q-jvm1"),
        InterviewQuestionDTO.createFollowUp(1, "QF1: 堆为什么分代？", "JVM", "JVM", "q-jvm1", 1,
            InterviewQuestionDTO.FOLLOW_UP_WHY, List.of("分代假设")).withQuestionId("q-jf1"),
        InterviewQuestionDTO.createMain(2, "Q2: Redis 持久化？", "REDIS", "Redis",
            "RDB/AOF", 3, List.of("RDB", "AOF")).withQuestionId("q-redis1")
    );
  }

  /**
   * 测试用便捷调用：把「池内第 answeredIndex 个候选」当作刚答完的题。
   *
   * <p>生产代码只按**标识 + 是否问过**选下一题；这里按下标取起始题只是为了少写样板。
   */
  private static InterviewQuestionDTO selectNext(List<InterviewQuestionDTO> candidates,
                                                 int answeredIndex, TurnEvaluation evaluation) {
    if (answeredIndex < 0) {
      return AdaptiveInterviewPolicy.selectNext(candidates, Set.of(), null, evaluation);
    }
    InterviewQuestionDTO answered = candidates.get(answeredIndex);
    Set<String> asked = candidates.stream().limit(answeredIndex + 1L)
        .map(InterviewQuestionDTO::questionId).collect(Collectors.toSet());
    return AdaptiveInterviewPolicy.selectNext(candidates, asked, answered, evaluation);
  }

  /** 合并后的真实题库：0 R1 / 1 RF1 / 2 Q1 / 3 QF1 / 4 Q2 */
  private static List<InterviewQuestionDTO> mergedPool() {
    return InterviewQuestionService.mergeQuestionBatches(resumeBatch(), directionBatch());
  }

  private static TurnEvaluation eval(AnswerState state) {
    return new TurnEvaluation(TurnEvaluation.defaultScoreFor(state), 0.5,
        List.of(), List.of("关键缺口"), state, "继续验证关键缺口", true);
  }

  // ===== 1. 题目合并 =====

  @Test
  @DisplayName("题目合并：元数据不丢，且索引偏移后追问仍归属其主问题")
  void mergeKeepsMetadataAndCorrectFollowUpOwnership() {
    List<InterviewQuestionDTO> merged = mergedPool();

    assertThat(merged).hasSize(5);
    // 方向题批次整体后移 2 位，其追问的父索引必须同步偏移到 2
    assertThat(merged.get(2).question()).isEqualTo("Q1: JVM 内存模型？");
    assertThat(merged.get(3).question()).isEqualTo("QF1: 堆为什么分代？");
    assertThat(merged.get(3).parentQuestionId()).isEqualTo("q-jvm1");
    assertThat(merged.get(1).parentQuestionId()).isEqualTo("q-r1");

    // 元数据（difficulty / followUpType / expectedPoints）必须原样保留
    assertThat(merged.get(2).difficulty()).isEqualTo(4);
    assertThat(merged.get(2).expectedPoints()).containsExactly("堆", "栈", "方法区");
    assertThat(merged.get(3).followUpType()).isEqualTo(InterviewQuestionDTO.FOLLOW_UP_WHY);
    assertThat(merged.get(3).expectedPoints()).containsExactly("分代假设");
    assertThat(merged.get(2).followUpType()).isNull();
  }

  @Test
  @DisplayName("合并后的选题：答好进入本主题追问，不会错拿到上一批的追问")
  void adaptiveSelectionWorksOnMergedPool() {
    List<InterviewQuestionDTO> merged = mergedPool();

    // 第一批的追问正常（回归：合并未破坏第一批归属）
    assertThat(selectNext(merged, 0, eval(AnswerState.GOOD)).questionIndex())
        .isEqualTo(1);

    // 关键回归点：答好 Q1(2) 应进入 QF1(3)，而不是上一批残留的 RF1(1)
    InterviewQuestionDTO next = selectNext(merged, 2, eval(AnswerState.GOOD));
    assertThat(next.questionIndex()).isEqualTo(3);
    assertThat(next.question()).isEqualTo("QF1: 堆为什么分代？");
    assertThat(next.parentQuestionId()).isEqualTo("q-jvm1");
  }

  // ===== 2. 跳过追问 =====

  @Test
  @DisplayName("跳过追问：答不上就中断追问组切下一主问题，主问题耗尽即结束")
  void skipFollowUpOnWeakAnswerThenFinish() {
    List<InterviewQuestionDTO> merged = mergedPool();

    // 答不上 Q1(2) → 跳过 QF1(3)，直接切 Q2(4)
    InterviewQuestionDTO afterWeak =
        selectNext(merged, 2, eval(AnswerState.NO_ANSWER));
    assertThat(afterWeak.questionIndex()).isEqualTo(4);
    assertThat(afterWeak.isFollowUp()).isFalse();

    // Q2 是最后一个主问题且无追问 → 面试结束
    assertThat(selectNext(merged, 4, eval(AnswerState.GOOD))).isNull();

    // 答错（WRONG/WEAK）与答不上同属「不深挖」
    assertThat(selectNext(merged, 2, eval(AnswerState.WRONG)).questionIndex())
        .isEqualTo(4);
    assertThat(selectNext(merged, 2, eval(AnswerState.WEAK)).questionIndex())
        .isEqualTo(4);
  }

  // ===== 3. 刷新恢复 =====

  @Test
  @DisplayName("刷新恢复：缓存失效后按 DB 重建，被跳过的追问不会进轨迹")
  void restoreFromDatabaseKeepsSkippedFollowUpUnanswered() throws Exception {
    // 实际发生：R1(0) 已答 → RF1(1) 被策略跳过（没有它的答案行）→ Q1(2) 为当前题
    InterviewSessionEntity entity = entity("s1", true);
    entity.setQuestionsJson(objectMapper.writeValueAsString(mergedPool()));
    entity.setCurrentQuestionIndex(2);
    entity.setCurrentQuestionId("q-jvm1");
    entity.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
    when(persistenceService.findBySessionId("s1")).thenReturn(Optional.of(entity));
    when(persistenceService.findTurnsBySessionId("s1")).thenReturn(List.of(
        new InterviewTurnDTO("q-r1", 1, 0, "R1: 介绍你最有挑战的项目", "项目", null,
            "我负责支付模块的重构", InterviewAnswerEntity.AnswerState.ANSWERED, null, null,
            InterviewTurnDTO.ACTION_NEXT_MAIN, null, List.of(), null)));

    InterviewSessionDTO dto = service.getSession("s1");

    // 候选素材完整保留（恢复不能裁剪，否则决策与历史都失真）
    assertThat(dto.candidates()).hasSize(5);
    assertThat(dto.currentQuestionIndex()).isEqualTo(2);
    assertThat(dto.currentQuestionId()).isEqualTo("q-jvm1");
    assertThat(dto.currentQuestion()).isNotNull();
    assertThat(dto.status()).isEqualTo(SessionStatus.IN_PROGRESS);
    // adaptive 是 DB 权威：缓存失效后仍必须按自适应语义恢复
    assertThat(dto.adaptive()).isTrue();

    // 关键回归点：轨迹里只有真实发生过的那一轮，被跳过的候选追问（q-rf1 / q-jf1）不在其中
    assertThat(dto.turns()).hasSize(1);
    assertThat(dto.turns().get(0).questionId()).isEqualTo("q-r1");
    assertThat(dto.turns().get(0).userAnswer()).isEqualTo("我负责支付模块的重构");
    assertThat(dto.turns())
        .noneSatisfy(turn -> assertThat(turn.questionId()).isIn("q-rf1", "q-jf1"));

    // JSON 往返后结构化元数据仍然可用（决策与评估依赖它们）
    assertThat(dto.candidates().get(2).difficulty()).isEqualTo(4);
    assertThat(dto.candidates().get(3).followUpType()).isEqualTo(InterviewQuestionDTO.FOLLOW_UP_WHY);
    assertThat(dto.candidates().get(3).isFollowUp()).isTrue();
  }

  @Test
  @DisplayName("刷新恢复：缓存命中时不再回源 DB")
  void cacheHitDoesNotTouchDatabase() throws Exception {
    cacheStore.put("s1", new CachedSession("s1", "", null, null, null,
        mergedPool(), 2, SessionStatus.IN_PROGRESS, true, objectMapper));

    InterviewSessionDTO dto = service.getSession("s1");

    assertThat(dto.candidates()).hasSize(5);
    verify(persistenceService, never()).findBySessionId(anyString());
  }

  // ===== 4. 提前结束 =====

  @Test
  @DisplayName("提前结束：置 COMPLETED、写版本、请求评估并入队整场评估")
  void earlyFinishMarksCompletedAndEnqueuesEvaluation() {
    cacheStore.put("s1", new CachedSession("s1", "", null, null, null,
        mergedPool(), 2, SessionStatus.IN_PROGRESS, true, objectMapper));
    when(persistenceService.findBySessionId("s1")).thenReturn(Optional.of(inProgress("s1", 2)));

    service.completeInterview("s1");

    // 结束与逐轮推进同一条路径：状态、评估请求、版本一次提交（P4-9a）
    assertThat(committed.action()).isEqualTo("COMPLETE");
    verify(sessionCache).updateSessionStatus("s1", SessionStatus.COMPLETED);
    verify(sessionCache).updateTurnVersion("s1", 1);
    verify(evaluateStreamProducer).sendEvaluateTask(eq("s1"), anyLong());
  }

  @Test
  @DisplayName("提前结束：已结束的会话不允许重复交卷")
  void rejectsSecondFinish() {
    cacheStore.put("s1", new CachedSession("s1", "", null, null, null,
        mergedPool(), 5, SessionStatus.COMPLETED, true, objectMapper));
    // 已结束的判据在数据库（缓存只是副本）
    InterviewSessionEntity finished = inProgress("s1", 5);
    finished.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
    when(persistenceService.findBySessionId("s1")).thenReturn(Optional.of(finished));

    assertThatThrownBy(() -> service.completeInterview("s1"))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.INTERVIEW_ALREADY_COMPLETED.getCode());

    verify(evaluateStreamProducer, never()).sendEvaluateTask(anyString(), anyLong());
  }

  @Test
  @DisplayName("提前结束后刷新恢复：状态为 COMPLETED，当前题不成立")
  void restoredAfterEarlyFinishHasNoCurrentQuestion() throws Exception {
    // 提前结束时索引可能停在未问过的追问上，恢复后不能把该题当成「当前题」
    InterviewSessionEntity entity = entity("s1", true);
    entity.setQuestionsJson(objectMapper.writeValueAsString(mergedPool()));
    entity.setCurrentQuestionIndex(3);
    entity.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
    when(persistenceService.findBySessionId("s1")).thenReturn(Optional.of(entity));
    // 轨迹只含真实发生过的两轮（R1 与 Q1）；被跳过的 RF1 与提前结束停在的 QF1 都不在
    when(persistenceService.findTurnsBySessionId("s1")).thenReturn(List.of(
        new InterviewTurnDTO("q-r1", 1, 0, "R1: 介绍你最有挑战的项目", "项目", null,
            "我负责支付模块的重构", InterviewAnswerEntity.AnswerState.ANSWERED, null, null,
            InterviewTurnDTO.ACTION_NEXT_MAIN, null, List.of(), null),
        new InterviewTurnDTO("q-jvm1", 2, 2, "Q1: JVM 内存模型？", "JVM", null,
            "只记得堆和栈", InterviewAnswerEntity.AnswerState.ANSWERED, null, null,
            InterviewTurnDTO.ACTION_FINISH_USER, null, List.of(), null)));

    InterviewSessionDTO dto = service.getSession("s1");

    assertThat(dto.status()).isEqualTo(SessionStatus.COMPLETED);
    assertThat(dto.currentQuestionId()).as("终态会话不再暴露待答题标识").isNull();
    assertThat(dto.currentQuestion()).as("终态会话不再有当前题").isNull();
    assertThat(dto.turns())
        .extracting(InterviewTurnDTO::questionId)
        .containsExactly("q-r1", "q-jvm1");
  }

  // ===== 辅助 =====

  private static InterviewSessionEntity entity(String sessionId, boolean adaptive) {
    InterviewSessionEntity e = new InterviewSessionEntity();
    e.setSessionId(sessionId);
    e.setAdaptive(adaptive);
    e.setLlmProvider("glm");
    return e;
  }

  private static InterviewAnswerEntity answer(int questionIndex, String userAnswer) {
    InterviewAnswerEntity a = new InterviewAnswerEntity();
    a.setQuestionIndex(questionIndex);
    a.setUserAnswer(userAnswer);
    return a;
  }
}
