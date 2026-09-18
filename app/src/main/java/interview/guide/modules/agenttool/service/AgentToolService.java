package interview.guide.modules.agenttool.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agenttool.contract.AgentToolSchemaExporter;
import interview.guide.modules.agenttool.dto.ToolInfoDTO;
import interview.guide.modules.agenttool.dto.ToolResponse;
import interview.guide.modules.agenttool.model.AgentToolName;
import interview.guide.modules.agenttool.model.AgentToolRequests;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.model.SessionListItemDTO;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.profile.service.SkillProfileQueryService;
import interview.guide.modules.resume.model.ResumeContentDTO;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeVersionDTO;
import interview.guide.modules.resume.model.ResumeVersionEntity;
import interview.guide.modules.resume.service.ResumeHistoryService;
import interview.guide.modules.resume.service.ResumePatchApplyService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import interview.guide.modules.resume.service.ResumeVersionService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Agent Tool 分派服务。
 *
 * <p>薄适配层：只负责 Tool 路由、参数校验与现有业务 Service 的调用转发，
 * 不包含任何业务逻辑。数据归属与业务规则仍由各业务模块负责。
 *
 * <p><b>入参契约（ARCH-1）</b>：入参先按 {@link AgentToolName#getRequestType()} 反序列化为
 * 类型化请求模型，再做「未知参数拒绝 + jakarta validation 校验」。因此这里不再手写逐个字段的
 * 提取与类型转换——那正是契约漂移的来源（未知字段曾被静默忽略，调用方以为传了、服务端其实没用）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentToolService {

  private final ResumeHistoryService resumeHistoryService;
  private final ResumePersistenceService resumePersistenceService;
  private final InterviewPersistenceService interviewPersistenceService;
  private final InterviewHistoryService interviewHistoryService;
  private final InterviewSessionService interviewSessionService;
  private final KnowledgeBaseListService knowledgeBaseListService;
  private final KnowledgeBaseQueryService knowledgeBaseQueryService;
  private final InterviewSkillService interviewSkillService;
  private final SkillProfileQueryService skillProfileQueryService;
  private final ResumeVersionService resumeVersionService;
  private final ResumePatchApplyService resumePatchApplyService;
  private final interview.guide.modules.job.service.JobDescriptionService jobDescriptionService;
  private final ObjectMapper objectMapper;
  private final Validator validator;

  /**
   * 返回全部 Tool 的元信息，供 Agent Runtime 做 Tool Discovery。
   *
   * <p>inputSchema 由类型化请求模型**实时导出**为真正的 JSON Schema——此前是一份手写字符串
   * （`{"resumeId": Long, optional}` 连合法 JSON 都不是），是契约漂移的第二来源。
   */
  public List<ToolInfoDTO> listTools() {
    return List.of(AgentToolName.values()).stream()
        .map(tool -> new ToolInfoDTO(
            tool.getName(),
            tool.getDescription(),
            tool.getPermission(),
            writeSchema(tool)))
        .toList();
  }

  /**
   * 执行指定 Tool。
   *
   * <p>先按名称解析 Tool（不存在直接报错），再把入参收进该 Tool 的类型化请求模型，
   * 最后通过 switch 分派到对应的业务 Service 转发方法。每个 handler 只做转发，
   * 不承载业务规则。
   */
  public ToolResponse execute(String toolName, Map<String, Object> arguments) {
    AgentToolName tool = AgentToolName.from(toolName)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.AGENT_TOOL_NOT_FOUND, "未知 Tool: " + toolName));
    Map<String, Object> args = arguments == null ? Map.of() : arguments;
    log.info("Agent Tool execute: tool={}, args={}", toolName, args.keySet());
    return switch (tool) {
      case GET_RESUME_LIST -> executeGetResumeList(parse(tool, args));
      case GET_SKILL_PROFILE -> executeGetSkillProfile(parse(tool, args));
      case GET_RESUME_VERSION -> executeGetResumeVersion(parse(tool, args));
      case GET_RESUME_ANALYSIS -> executeGetResumeAnalysis(parse(tool, args));
      case GET_RESUME -> executeGetResume(parse(tool, args));
      case GET_JOB -> executeGetJob(parse(tool, args));
      case GET_INTERVIEW_HISTORY -> executeGetInterviewHistory(parse(tool, args));
      case GET_INTERVIEW_REPORT -> executeGetInterviewReport(parse(tool, args));
      case LIST_KNOWLEDGE_BASES -> executeListKnowledgeBases(parse(tool, args));
      case SEARCH_KNOWLEDGE -> executeSearchKnowledge(parse(tool, args));
      case LIST_SKILLS -> executeListSkills(parse(tool, args));
      case CREATE_INTERVIEW -> executeCreateInterview(parse(tool, args));
      case APPLY_RESUME_PATCHES -> executeApplyResumePatches(parse(tool, args));
    };
  }

  /** 简历列表：含最新分析分数与面试次数，用于 Agent 判断用户简历概况 */
  private ToolResponse executeGetResumeList(AgentToolRequests.GetResumeList request) {
    return new ToolResponse(
        AgentToolName.GET_RESUME_LIST.getName(),
        resumeHistoryService.getAllResumes());
  }

  /** 技能画像：聚合分 + 证据明细，供 Agent 做「我 XX 水平怎么样」类回答 */
  private ToolResponse executeGetSkillProfile(AgentToolRequests.GetSkillProfile request) {
    return new ToolResponse(
        AgentToolName.GET_SKILL_PROFILE.getName(),
        skillProfileQueryService.getProfileWithEvidence());
  }

  /**
   * 简历结构化版本：优化子图取数入口。
   * 默认最新 ACTIVE 版本；带 version 时精确定位。
   */
  private ToolResponse executeGetResumeVersion(AgentToolRequests.GetResumeVersion request) {
    ResumeVersionEntity version = request.version() != null
        ? resumeVersionService.getByResumeVersion(request.resumeId(), request.version())
        : resumeVersionService.getActiveVersion(request.resumeId());
    return new ToolResponse(
        AgentToolName.GET_RESUME_VERSION.getName(),
        ResumeVersionDTO.from(version, objectMapper));
  }

  /**
   * JD 完整内容：解析文本 + 元信息（P2-5）。
   * 简历优化 JD_TARGETED 模式与 JD 匹配分析的取数入口。
   */
  private ToolResponse executeGetJob(AgentToolRequests.GetJob request) {
    var job = jobDescriptionService.get(request.jobId());
    return new ToolResponse(
        AgentToolName.GET_JOB.getName(),
        new JobDetailPayload(job.getId(), job.getTitle(), job.getCompany(),
            job.getContentText(), job.getCreatedAt().toString()));
  }

  /** JD 取数响应结构（contentText 全文；截断由 Python Token 纪律处理） */
  public record JobDetailPayload(
      Long id, String title, String company, String contentText, String createdAt) {}

  /** 简历最新分析结果：取最近一次分析，分析未完成或不存在时按业务错误返回 */
  private ToolResponse executeGetResumeAnalysis(AgentToolRequests.GetResumeAnalysis request) {
    ResumeAnalysisResponse analysis =
        resumePersistenceService.getLatestAnalysisAsDTO(request.resumeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND,
                "简历分析结果不存在: resumeId=" + request.resumeId()));
    return new ToolResponse(AgentToolName.GET_RESUME_ANALYSIS.getName(), analysis);
  }

  /**
   * 简历完整内容：解析文本 + 元信息，供 Agent 做内容级分析与简历优化。
   *
   * <p>maxChars 可选，用于服务端截断（Token 纪律）；默认 20000 字符，
   * 覆盖绝大多数简历文本长度。
   */
  private ToolResponse executeGetResume(AgentToolRequests.GetResume request) {
    ResumeEntity resume = resumePersistenceService.findById(request.resumeId())
        .orElseThrow(() -> new BusinessException(
            ErrorCode.RESUME_NOT_FOUND, "简历不存在: id=" + request.resumeId()));

    String text = resume.getResumeText();
    if (text != null && request.maxChars() != null && text.length() > request.maxChars()) {
      text = text.substring(0, Math.max(request.maxChars(), 0));
    }
    return new ToolResponse(AgentToolName.GET_RESUME.getName(), new ResumeContentDTO(
        resume.getId(),
        resume.getOriginalFilename(),
        text,
        resume.getAnalyzeStatus(),
        resume.getUploadedAt()));
  }

  /**
   * 面试历史列表：带 resumeId 时按简历过滤，否则返回全部；
   * Entity 统一转换为 SessionListItemDTO，避免直接暴露 JPA 实体。
   */
  private ToolResponse executeGetInterviewHistory(AgentToolRequests.GetInterviewHistory request) {
    List<SessionListItemDTO> items = request.resumeId() != null
        ? interviewPersistenceService.findByResumeId(request.resumeId())
            .stream()
            .map(SessionListItemDTO::from)
            .toList()
        : interviewPersistenceService.findAll()
            .stream()
            .map(SessionListItemDTO::from)
            .toList();
    return new ToolResponse(AgentToolName.GET_INTERVIEW_HISTORY.getName(), items);
  }

  /** 单场面试完整报告（只读查询），供 Agent 分析用户表现 */
  private ToolResponse executeGetInterviewReport(AgentToolRequests.GetInterviewReport request) {
    InterviewDetailDTO detail = interviewHistoryService.getInterviewDetail(request.sessionId());
    return new ToolResponse(AgentToolName.GET_INTERVIEW_REPORT.getName(), detail);
  }

  /** 知识库列表：让 Agent 了解用户有哪些可用知识库，再决定是否检索 */
  private ToolResponse executeListKnowledgeBases(AgentToolRequests.ListKnowledgeBases request) {
    return new ToolResponse(
        AgentToolName.LIST_KNOWLEDGE_BASES.getName(),
        knowledgeBaseListService.listKnowledgeBases());
  }

  /** RAG 问答：复用 Java 侧知识库查询链路（查询改写 + pgvector 检索 + LLM 作答） */
  private ToolResponse executeSearchKnowledge(AgentToolRequests.SearchKnowledge request) {
    QueryResponse response = knowledgeBaseQueryService.queryKnowledgeBase(
        new QueryRequest(request.knowledgeBaseIds(), request.question()));
    return new ToolResponse(AgentToolName.SEARCH_KNOWLEDGE.getName(), response);
  }

  /** 面试技能方向列表：Agent 据此向用户推荐面试方向 */
  private ToolResponse executeListSkills(AgentToolRequests.ListSkills request) {
    return new ToolResponse(
        AgentToolName.LIST_SKILLS.getName(),
        interviewSkillService.getAllSkills());
  }

  /**
   * 创建模拟面试会话（CONFIRM_WRITE：必须用户在前端确认后才由 Agent 调用）。
   *
   * <p>薄封装：复用 Java Interview Engine 现有创建链路（含 requestId 幂等与
   * 未完成会话复用）。返回 InterviewSessionDTO，Agent 据此回传 sessionId 跳转。
   */
  private ToolResponse executeCreateInterview(AgentToolRequests.CreateInterview request) {
    CreateInterviewRequest createRequest = new CreateInterviewRequest(
        request.resumeText(),
        request.plannedDurationMinutes(),
        normalizeFocus(request.requiredTopics()),
        null,
        request.resumeId(),
        Boolean.TRUE.equals(request.forceCreate()),
        null,
        request.skillId(),
        request.difficulty(),
        null,
        null,
        request.requestId(),
        true,  // P4-3：Agent 发起的面试默认开启逐题评估+自适应选题
        normalizeFocus(request.focusCategories())
    );
    InterviewSessionDTO session = interviewSessionService.createSession(createRequest);
    return new ToolResponse(AgentToolName.CREATE_INTERVIEW.getName(), session);
  }

  /**
   * 应用简历优化提案（CONFIRM_WRITE：用户在 ResumeOptimizationBlock 上勾选确认后调用）。
   *
   * <p>按 proposalId 读取已落库提案，逐条 JSON path 应用（oldValue 一致性校验），
   * 生成新版本（AI_OPTIMIZE，原版本不动）。返回新版本信息供 NavigationBlock 跳转。
   */
  private ToolResponse executeApplyResumePatches(AgentToolRequests.ApplyResumePatches request) {
    ResumeVersionEntity newVersion = resumePatchApplyService.applyPatches(
        request.proposalId(), request.patchIds() != null ? request.patchIds() : List.of());
    return new ToolResponse(
        AgentToolName.APPLY_RESUME_PATCHES.getName(),
        Map.of(
            "proposalId", request.proposalId(),
            "resumeId", newVersion.getResumeId(),
            "versionId", newVersion.getId(),
            "version", newVersion.getVersion()));
  }

  /** 去掉 focus 里的空白项（契约层不管格式细节，业务层按「未命中即忽略」处理） */
  private List<String> normalizeFocus(List<String> focusCategories) {
    if (focusCategories == null) {
      return List.of();
    }
    return focusCategories.stream()
        .filter(item -> item != null && !item.isBlank())
        .toList();
  }

  /**
   * 入参 → 类型化请求模型。
   *
   * <p>三步都给出**可读的失败原因**，而不是静默忽略：未知参数列出「实际传入」与「可用参数」；
   * 类型不匹配指出字段；约束不满足给出中文说明。这样调用方（Agent）能直接看出要改什么。
   */
  @SuppressWarnings("unchecked")
  private <T> T parse(AgentToolName tool, Map<String, Object> arguments) {
    rejectUnknownParameters(tool, arguments);
    Object parsed;
    try {
      parsed = objectMapper.convertValue(arguments, tool.getRequestType());
    } catch (RuntimeException e) {
      throw new BusinessException(ErrorCode.AGENT_TOOL_ARGUMENT_INVALID,
          "参数类型错误（" + tool.getName() + "）: " + e.getMessage());
    }
    validate(tool, parsed);
    return (T) parsed;
  }

  /** 未知参数直接拒绝：静默忽略会让「调用方以为传了、服务端其实没用」长期隐身 */
  private void rejectUnknownParameters(AgentToolName tool, Map<String, Object> arguments) {
    List<String> allowed = AgentToolSchemaExporter.parameterNames(tool.getRequestType());
    List<String> unknown = arguments.keySet().stream()
        .filter(key -> !allowed.contains(key))
        .sorted()
        .toList();
    if (!unknown.isEmpty()) {
      throw new BusinessException(ErrorCode.AGENT_TOOL_ARGUMENT_INVALID,
          "存在未知参数（" + tool.getName() + "）: " + unknown + "；可用参数: " + allowed);
    }
  }

  /** jakarta validation 校验：约束与导出的 JSON Schema 同源（都写在同一份请求模型上） */
  private void validate(AgentToolName tool, Object request) {
    Set<ConstraintViolation<Object>> violations = validator.validate(request);
    if (violations.isEmpty()) {
      return;
    }
    String detail = violations.stream()
        .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
        .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
        .collect(Collectors.joining("；"));
    throw new BusinessException(ErrorCode.AGENT_TOOL_ARGUMENT_INVALID,
        "参数无效（" + tool.getName() + "）: " + detail);
  }

  /** 导出该 Tool 的 JSON Schema 字符串；导出异常不影响 Discovery 的其他 Tool */
  private String writeSchema(AgentToolName tool) {
    try {
      return objectMapper.writeValueAsString(AgentToolSchemaExporter.exportTool(tool));
    } catch (RuntimeException e) {
      log.error("导出 Tool Schema 失败: tool={}", tool.getName(), e);
      return "{}";
    }
  }
}
