package interview.guide.modules.agenttool.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agenttool.service.AgentToolService;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.profile.service.SkillProfileQueryService;
import interview.guide.modules.resume.model.ResumeContentDTO;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.service.ResumeHistoryService;
import interview.guide.modules.resume.service.ResumePatchApplyService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import interview.guide.modules.resume.service.ResumeVersionService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Agent Tool 的**契约行为测试**（ARCH-1）。
 *
 * <p>契约测试容易只验「参数能传进来」，而这正是 P3 踩过的坑：`focus` 曾是纯装饰，
 * 参数存在、界面显示、业务毫无变化。本类只问一件事——**这个参数真的改变了业务行为吗**：
 * 每组断言都落到「下游 Service 实际收到了什么」或「服务端真的按它做了选择」。
 *
 * <p>依赖取舍：只对断言涉及的 Service 建 mock，其余传 mock 占位，避免为了构造
 * 15 个依赖而把测试写成样板（也要避免 @InjectMocks 在缺少 mock 时静默注入 null）。
 */
class AgentToolContractBehaviorTest {

  private final ResumePersistenceService resumePersistenceService =
      mock(ResumePersistenceService.class);
  private final InterviewSessionService interviewSessionService =
      mock(InterviewSessionService.class);

  /** 真实 Validator：入参校验本身是契约的一部分，不能 mock 掉 */
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private AgentToolService service;

  @BeforeEach
  void setUp() {
    service = new AgentToolService(
        mock(ResumeHistoryService.class),
        resumePersistenceService,
        mock(InterviewPersistenceService.class),
        mock(InterviewHistoryService.class),
        interviewSessionService,
        mock(KnowledgeBaseListService.class),
        mock(KnowledgeBaseQueryService.class),
        mock(InterviewSkillService.class),
        mock(SkillProfileQueryService.class),
        mock(ResumeVersionService.class),
        mock(ResumePatchApplyService.class),
        mock(interview.guide.modules.job.service.JobDescriptionService.class),
        new ObjectMapper(),
        validator);
    when(interviewSessionService.createSession(any()))
        .thenReturn(mock(InterviewSessionDTO.class));
  }

  @Test
  @DisplayName("resumeId 决定取哪一份简历：传 A 取 A、传 B 取 B（而不是「反正返回一份」）")
  void resumeIdSelectsTheRequestedResume() {
    when(resumePersistenceService.findById(11L))
        .thenReturn(Optional.of(resume(11L, "甲的简历：Kafka 重平衡排查")));
    when(resumePersistenceService.findById(22L))
        .thenReturn(Optional.of(resume(22L, "乙的简历：JVM GC 调优")));

    ResumeContentDTO first =
        (ResumeContentDTO) service.execute("get_resume", Map.of("resumeId", 11)).data();
    ResumeContentDTO second =
        (ResumeContentDTO) service.execute("get_resume", Map.of("resumeId", 22)).data();

    assertThat(first.id()).isEqualTo(11L);
    assertThat(first.resumeText()).contains("Kafka 重平衡排查");
    assertThat(second.id()).isEqualTo(22L);
    assertThat(second.resumeText()).contains("JVM GC 调优");
  }

  @Test
  @DisplayName("focusCategories 真的到达面试引擎（不是只出现在界面上）")
  void focusCategoriesReachInterviewEngine() {
    service.execute("create_interview", Map.of(
        "skillId", "java-backend",
        "focusCategories", List.of("JVM", "REDIS")));

    assertThat(capturedCreateRequest().focusCategories()).containsExactly("JVM", "REDIS");
  }

  @Test
  @DisplayName("resumeText 真的透传：Java 侧靠它判断是否存在简历题分支")
  void resumeTextReachesInterviewEngine() {
    service.execute("create_interview", Map.of(
        "skillId", "java-backend",
        "resumeText", "项目经历：基于 LangGraph 的智能体平台"));

    assertThat(capturedCreateRequest().resumeText())
        .as("出题时 hasResume 由这个字段决定；丢了它 Agent 发起的面试就退化成通用面试")
        .contains("LangGraph");
  }

  @Test
  @DisplayName("时长与必要覆盖到达面试引擎，缺省时交给引擎采用默认计划")
  void planControlsReachInterviewEngine() {
    service.execute("create_interview", Map.of(
        "skillId", "java-backend",
        "plannedDurationMinutes", 30,
        "requiredTopics", List.of("JVM", "PROJECT")));
    assertThat(capturedCreateRequest().plannedDurationMinutes()).isEqualTo(30);
    assertThat(capturedCreateRequest().requiredTopics()).containsExactly("JVM", "PROJECT");
    assertThat(capturedCreateRequest().questionCount()).isNull();

    service.execute("create_interview", Map.of("skillId", "java-backend"));
    assertThat(capturedCreateRequest().plannedDurationMinutes()).isNull();
    assertThat(capturedCreateRequest().requiredTopics()).isEmpty();
  }

  @Test
  @DisplayName("requestId 真的透传为幂等键，缺省则为空（退化为非幂等创建）")
  void requestIdReachesCreationAsIdempotencyKey() {
    service.execute("create_interview", Map.of(
        "skillId", "java-backend", "requestId", "confirm-abc123"));
    assertThat(capturedCreateRequest().requestId()).isEqualTo("confirm-abc123");

    service.execute("create_interview", Map.of("skillId", "java-backend"));
    assertThat(capturedCreateRequest().requestId()).isNull();
  }

  @Test
  @DisplayName("未知参数 / 类型错误 / 缺必填 / 超出范围：四类都有明确、可读的失败结果")
  void invalidArgumentsAreRejectedWithReadableReasons() {
    // ① 未知参数：静默忽略会让「调用方以为传了、服务端其实没用」长期隐身
    assertThatThrownBy(() -> service.execute(
        "get_resume", Map.of("resumeId", 1, "resume_id", 2)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.AGENT_TOOL_ARGUMENT_INVALID.getCode())
        .hasMessageContaining("resume_id")
        .hasMessageContaining("resumeId");

    // ② 类型错误
    assertThatThrownBy(() -> service.execute("get_resume", Map.of("resumeId", "abc")))
        .hasFieldOrPropertyWithValue("code", ErrorCode.AGENT_TOOL_ARGUMENT_INVALID.getCode())
        .hasMessageContaining("参数类型错误");

    // ③ 缺必填：错误信息点名是哪个字段
    assertThatThrownBy(() -> service.execute(
        "search_knowledge", Map.of("knowledgeBaseIds", List.of(1))))
        .hasFieldOrPropertyWithValue("code", ErrorCode.AGENT_TOOL_ARGUMENT_INVALID.getCode())
        .hasMessageContaining("question");

    // ④ 约束不满足：中文说明直接指出越界
    assertThatThrownBy(() -> service.execute(
        "create_interview", Map.of("skillId", "java-backend", "plannedDurationMinutes", 121)))
        .hasFieldOrPropertyWithValue("code", ErrorCode.AGENT_TOOL_ARGUMENT_INVALID.getCode())
        .hasMessageContaining("120");
  }

  @Test
  @DisplayName("listTools 暴露真实 JSON Schema（不再是手写字符串）")
  void listToolsExposesRealJsonSchema() {
    String schema = service.listTools().stream()
        .filter(tool -> tool.name().equals("create_interview"))
        .findFirst()
        .orElseThrow()
        .inputSchema();

    assertThat(schema).contains("\"additionalProperties\":false");
    assertThat(schema).contains("\"required\":[\"skillId\"]");
    assertThat(schema).contains("\"requestId\"");
  }

  /**
   * 捕获下游实际收到的创建请求（契约测试的断言对象）。
   *
   * <p>取「最后一次调用」——同一用例里常要对照「显式传参」与「缺省」两种调用，
   * 按次数严格校验只会让断言被调用次数绑架。
   */
  private CreateInterviewRequest capturedCreateRequest() {
    ArgumentCaptor<CreateInterviewRequest> captor =
        ArgumentCaptor.forClass(CreateInterviewRequest.class);
    verify(interviewSessionService, atLeastOnce()).createSession(captor.capture());
    return captor.getAllValues().getLast();
  }

  private static ResumeEntity resume(long id, String text) {
    ResumeEntity resume = new ResumeEntity();
    resume.setId(id);
    resume.setOriginalFilename("resume-" + id + ".pdf");
    resume.setResumeText(text);
    return resume;
  }
}
