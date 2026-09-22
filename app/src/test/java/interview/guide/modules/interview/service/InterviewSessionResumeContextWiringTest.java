package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewResumeContext;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.resume.model.ResumeContentJson;
import interview.guide.modules.resume.model.ResumeVersionEntity;
import interview.guide.modules.resume.service.ResumePersistenceService;
import interview.guide.modules.resume.service.ResumeVersionService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * 简历上下文接线（P4Q-1）：「只传简历 ID」必须真的问到简历里的内容。
 *
 * <p>缺陷现场：Java 出题只认调用方传入的 `resumeText`，而 Agent 侧恒传 null，
 * 于是 Agent 发起的面试**简历题分支从未生效**、等同于通用面试。
 * 本测试用**真实解析器**（只 mock 简历读取）把链路走通：
 * resumeId → 结构化版本 → 出题文本 → 落库快照 → 缓存来源 → 返回 DTO。
 */
@DisplayName("简历上下文接线")
@ExtendWith(MockitoExtension.class)
class InterviewSessionResumeContextWiringTest {

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
  private ResumeVersionService resumeVersionService;
  @Mock
  private ResumePersistenceService resumePersistenceService;

  @Mock
  private interview.guide.modules.profile.service.SkillProfileQueryService skillProfileQueryService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private InterviewSessionService service;
  private final AtomicReference<CachedSession> cachedSession = new AtomicReference<>();

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
        new InterviewResumeContextResolver(
            resumeVersionService, resumePersistenceService, objectMapper),
        skillProfileQueryService);

    when(questionService.generateQuestionsBySkill(
        any(), anyString(), anyString(), any(), anyInt(), any(), any(), any(), any()))
        .thenReturn(List.of(InterviewQuestionDTO.createMain(
            0, "Q1: 你在订单系统里做了什么？", "JAVA", "Java", null, 3, List.of())));
    doAnswer(invocation -> {
      cachedSession.set(new CachedSession(
          invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
          invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5),
          invocation.getArgument(6), invocation.getArgument(7, SessionStatus.class),
          invocation.getArgument(8), invocation.getArgument(9), invocation.getArgument(10),
          objectMapper));
      return null;
    }).when(sessionCache).saveSession(
        anyString(), anyString(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any());
    doAnswer(invocation -> {
      InterviewPlan plan = invocation.getArgument(1, InterviewPlan.class);
      cachedSession.get().setPlannedDurationMinutes(plan.plannedDurationMinutes());
      cachedSession.get().setRequiredTopicsJson(
          objectMapper.writeValueAsString(plan.requiredTopics()));
      return null;
    }).when(sessionCache).applyPlan(anyString(), any());
    when(sessionCache.getSession(anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(cachedSession.get()));
  }

  @Test
  @DisplayName("只传 resumeId：出题拿到的是简历内容（而不是空文本）")
  void resumeIdAloneDrivesQuestionGeneration() {
    when(resumeVersionService.getActiveVersion(7L)).thenReturn(activeVersion(7L, 2));

    service.createSession(request(7L, null));

    ArgumentCaptor<String> resumeText = ArgumentCaptor.forClass(String.class);
    verify(questionService).generateQuestionsBySkill(
        any(), anyString(), anyString(), resumeText.capture(), anyInt(), any(), any(), any(), any());
    assertThat(resumeText.getValue())
        .as("Agent 侧从来只传 resumeId；出题必须自己取到简历内容")
        .contains("Kafka 重平衡排查")
        .contains("个人负责消费端幂等改造");
  }

  @Test
  @DisplayName("落库与缓存都带上来源与版本：报告与复盘能说清依据哪份简历")
  void snapshotIsPersistedAndCached() {
    when(resumeVersionService.getActiveVersion(7L)).thenReturn(activeVersion(7L, 2));

    InterviewSessionDTO created = service.createSession(request(7L, null));

    ArgumentCaptor<InterviewResumeContext> persisted =
        ArgumentCaptor.forClass(InterviewResumeContext.class);
    ArgumentCaptor<InterviewPlan> plan = ArgumentCaptor.forClass(InterviewPlan.class);
    verify(persistenceService).saveSession(
        anyString(), any(), anyInt(), any(), any(), anyString(), anyString(), anyBoolean(),
        persisted.capture(), plan.capture());
    assertThat(persisted.getValue().source())
        .isEqualTo(InterviewResumeContext.Source.RESUME_VERSION);
    assertThat(persisted.getValue().version()).isEqualTo(2);
    assertThat(persisted.getValue().text()).contains("Kafka 重平衡排查");
    assertThat(plan.getValue().plannedDurationMinutes()).isEqualTo(20);

    ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Integer> version = ArgumentCaptor.forClass(Integer.class);
    verify(sessionCache).saveSession(
        anyString(), anyString(), any(), any(), any(), any(), anyInt(), any(), any(),
        source.capture(), version.capture());
    assertThat(source.getValue()).isEqualTo("RESUME_VERSION");
    assertThat(version.getValue()).isEqualTo(2);

    assertThat(created.resumeSource()).isEqualTo("RESUME_VERSION");
    assertThat(created.resumeVersion()).isEqualTo(2);
    assertThat(created.resumeText()).contains("Kafka 重平衡排查");
    assertThat(created.currentQuestion()).as("新建会话必须直接返回首个待答题").isNotNull();
    assertThat(created.currentQuestionId())
        .isEqualTo(created.currentQuestion().questionId());
  }

  @Test
  @DisplayName("无简历：来源记为 NONE，出题不拿到任何简历文本（不编造经历）")
  void withoutResumeNothingIsFabricated() {
    service.createSession(request(null, null));

    ArgumentCaptor<String> resumeText = ArgumentCaptor.forClass(String.class);
    verify(questionService).generateQuestionsBySkill(
        any(), anyString(), anyString(), resumeText.capture(), anyInt(), any(), any(), any(), any());
    assertThat(resumeText.getValue()).isNull();

    ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
    verify(sessionCache).saveSession(
        anyString(), anyString(), any(), any(), any(), any(), anyInt(), any(), any(),
        source.capture(), any());
    assertThat(source.getValue()).isEqualTo("NONE");
  }

  private static CreateInterviewRequest request(Long resumeId, String resumeText) {
    return new CreateInterviewRequest(
        resumeText, 5, resumeId, true, null, "java-backend", "mid", null, null, null, true,
        List.of());
  }

  private ResumeVersionEntity activeVersion(Long resumeId, int version) {
    ResumeContentJson content = new ResumeContentJson(
        new ResumeContentJson.BasicInfo("张三", null, null, null, "Java 后端实习"),
        List.of(),
        List.of(new ResumeContentJson.ExperienceItem(
            "某公司", "后端实习生", "2025-07", "2025-10", List.of("个人负责消费端幂等改造"))),
        List.of(new ResumeContentJson.ProjectItem(
            "订单系统", "开发", "2025-03", "2025-06", "Java / Kafka",
            List.of("Kafka 重平衡排查"))),
        List.of(),
        List.of());
    ResumeVersionEntity entity = new ResumeVersionEntity();
    entity.setResumeId(resumeId);
    entity.setVersion(version);
    entity.setContentJson(objectMapper.writeValueAsString(content));
    return entity;
  }
}
