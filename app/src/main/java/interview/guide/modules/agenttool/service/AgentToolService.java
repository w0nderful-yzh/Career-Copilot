package interview.guide.modules.agenttool.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agenttool.dto.ToolInfoDTO;
import interview.guide.modules.agenttool.dto.ToolResponse;
import interview.guide.modules.agenttool.model.AgentToolName;
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
import interview.guide.modules.resume.service.ResumePersistenceService;
import interview.guide.modules.resume.service.ResumeVersionService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Agent Tool 分派服务。
 *
 * <p>薄适配层：只负责 Tool 路由、参数校验与现有业务 Service 的调用转发，
 * 不包含任何业务逻辑。数据归属与业务规则仍由各业务模块负责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentToolService {

  /** 默认题目数量（Agent 未指定时使用，与前端创建面试默认一致） */
  private static final int DEFAULT_QUESTION_COUNT = 8;

  /** 每个 Tool 的输入参数 schema 描述，用于 Tool Discovery 时帮助 LLM 生成正确参数 */
  private static final Map<AgentToolName, String> INPUT_SCHEMAS = Map.ofEntries(
      Map.entry(AgentToolName.GET_RESUME_LIST, "{}"),
      Map.entry(AgentToolName.GET_SKILL_PROFILE, "{}"),
      Map.entry(AgentToolName.GET_RESUME_VERSION,
          "{\"resumeId\": Long, \"version\": Integer, optional}"),
      Map.entry(AgentToolName.GET_RESUME_ANALYSIS, "{\"resumeId\": Long}"),
      Map.entry(AgentToolName.GET_RESUME,
          "{\"resumeId\": Long, \"maxChars\": Integer, optional}"),
      Map.entry(AgentToolName.GET_INTERVIEW_HISTORY, "{\"resumeId\": Long, optional}"),
      Map.entry(AgentToolName.GET_INTERVIEW_REPORT, "{\"sessionId\": String}"),
      Map.entry(AgentToolName.LIST_KNOWLEDGE_BASES, "{}"),
      Map.entry(AgentToolName.SEARCH_KNOWLEDGE,
          "{\"knowledgeBaseIds\": List[Long], \"question\": String}"),
      Map.entry(AgentToolName.LIST_SKILLS, "{}"),
      Map.entry(AgentToolName.CREATE_INTERVIEW,
          "{\"skillId\": String, \"difficulty\": String, \"questionCount\": Integer, "
              + "optional, \"resumeId\": Long, optional, \"resumeText\": String, optional, "
              + "\"forceCreate\": Boolean, optional, \"requestId\": String, optional}"));

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
  private final ObjectMapper objectMapper;

  /** 返回全部 Tool 的元信息，供 Agent Runtime 做 Tool Discovery */
  public List<ToolInfoDTO> listTools() {
    return List.of(AgentToolName.values()).stream()
        .map(tool -> new ToolInfoDTO(
            tool.getName(),
            tool.getDescription(),
            tool.getPermission(),
            INPUT_SCHEMAS.get(tool)))
        .toList();
  }

  /**
   * 执行指定 Tool。
   *
   * <p>先按名称解析 Tool（不存在直接报错），再通过 switch 分派到对应的
   * 业务 Service 转发方法。每个 handler 只做参数提取与转发，不承载业务规则。
   */
  public ToolResponse execute(String toolName, Map<String, Object> arguments) {
    AgentToolName tool = AgentToolName.from(toolName)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.AGENT_TOOL_NOT_FOUND, "未知 Tool: " + toolName));
    log.info("Agent Tool execute: tool={}", toolName);
    return switch (tool) {
      case GET_RESUME_LIST -> executeGetResumeList();
      case GET_SKILL_PROFILE -> executeGetSkillProfile();
      case GET_RESUME_VERSION -> executeGetResumeVersion(arguments);
      case GET_RESUME_ANALYSIS -> executeGetResumeAnalysis(arguments);
      case GET_RESUME -> executeGetResume(arguments);
      case GET_INTERVIEW_HISTORY -> executeGetInterviewHistory(arguments);
      case GET_INTERVIEW_REPORT -> executeGetInterviewReport(arguments);
      case LIST_KNOWLEDGE_BASES -> executeListKnowledgeBases();
      case SEARCH_KNOWLEDGE -> executeSearchKnowledge(arguments);
      case LIST_SKILLS -> executeListSkills();
      case CREATE_INTERVIEW -> executeCreateInterview(arguments);
    };
  }

  /** 简历列表：含最新分析分数与面试次数，用于 Agent 判断用户简历概况 */
  private ToolResponse executeGetResumeList() {
    return new ToolResponse(
        AgentToolName.GET_RESUME_LIST.getName(),
        resumeHistoryService.getAllResumes());
  }

  /** 技能画像：聚合分 + 证据明细，供 Agent 做「我 XX 水平怎么样」类回答 */
  private ToolResponse executeGetSkillProfile() {
    return new ToolResponse(
        AgentToolName.GET_SKILL_PROFILE.getName(),
        skillProfileQueryService.getProfileWithEvidence());
  }

  /**
   * 简历结构化版本：优化子图取数入口。
   * 默认最新 ACTIVE 版本；带 version 时精确定位。
   */
  private ToolResponse executeGetResumeVersion(Map<String, Object> arguments) {
    Long resumeId = requireLong(arguments, "resumeId");
    ResumeVersionEntity version = arguments.containsKey("version")
        ? resumeVersionService.getByResumeVersion(resumeId, requireInt(arguments, "version"))
        : resumeVersionService.getActiveVersion(resumeId);
    return new ToolResponse(
        AgentToolName.GET_RESUME_VERSION.getName(),
        ResumeVersionDTO.from(version, objectMapper));
  }

  /** 简历最新分析结果：取最近一次分析，分析未完成或不存在时按业务错误返回 */
  private ToolResponse executeGetResumeAnalysis(Map<String, Object> arguments) {
    Long resumeId = requireLong(arguments, "resumeId");
    ResumeAnalysisResponse analysis = resumePersistenceService.getLatestAnalysisAsDTO(resumeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND,
            "简历分析结果不存在: resumeId=" + resumeId));
    return new ToolResponse(AgentToolName.GET_RESUME_ANALYSIS.getName(), analysis);
  }

  /**
   * 简历完整内容：解析文本 + 元信息，供 Agent 做内容级分析与简历优化。
   *
   * <p>maxChars 可选，用于服务端截断（Token 纪律）；默认 20000 字符，
   * 覆盖绝大多数简历文本长度。
   */
  private ToolResponse executeGetResume(Map<String, Object> arguments) {
    Long resumeId = requireLong(arguments, "resumeId");
    Integer maxChars = arguments.containsKey("maxChars")
        ? requireInt(arguments, "maxChars")
        : null;
    ResumeEntity resume = resumePersistenceService.findById(resumeId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.RESUME_NOT_FOUND, "简历不存在: id=" + resumeId));

    String text = resume.getResumeText();
    if (text != null && maxChars != null && text.length() > maxChars) {
      text = text.substring(0, Math.max(maxChars, 0));
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
  private ToolResponse executeGetInterviewHistory(Map<String, Object> arguments) {
    List<SessionListItemDTO> items = arguments.containsKey("resumeId")
        ? interviewPersistenceService.findByResumeId(requireLong(arguments, "resumeId"))
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
  private ToolResponse executeGetInterviewReport(Map<String, Object> arguments) {
    String sessionId = requireString(arguments, "sessionId");
    InterviewDetailDTO detail = interviewHistoryService.getInterviewDetail(sessionId);
    return new ToolResponse(AgentToolName.GET_INTERVIEW_REPORT.getName(), detail);
  }

  /** 知识库列表：让 Agent 了解用户有哪些可用知识库，再决定是否检索 */
  private ToolResponse executeListKnowledgeBases() {
    return new ToolResponse(
        AgentToolName.LIST_KNOWLEDGE_BASES.getName(),
        knowledgeBaseListService.listKnowledgeBases());
  }

  /** RAG 问答：复用 Java 侧知识库查询链路（查询改写 + pgvector 检索 + LLM 作答） */
  private ToolResponse executeSearchKnowledge(Map<String, Object> arguments) {
    List<Long> knowledgeBaseIds = requireLongList(arguments, "knowledgeBaseIds");
    String question = requireString(arguments, "question");
    QueryResponse response = knowledgeBaseQueryService.queryKnowledgeBase(
        new QueryRequest(knowledgeBaseIds, question));
    return new ToolResponse(AgentToolName.SEARCH_KNOWLEDGE.getName(), response);
  }

  /** 面试技能方向列表：Agent 据此向用户推荐面试方向 */
  private ToolResponse executeListSkills() {
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
  private ToolResponse executeCreateInterview(Map<String, Object> arguments) {
    String skillId = requireString(arguments, "skillId");
    Integer questionCount = arguments.containsKey("questionCount")
        ? requireInt(arguments, "questionCount")
        : null;
    Long resumeId = arguments.containsKey("resumeId")
        ? requireLong(arguments, "resumeId")
        : null;
    String resumeText = arguments.containsKey("resumeText")
        ? (String) arguments.get("resumeText")
        : null;
    String difficulty = arguments.containsKey("difficulty")
        ? requireString(arguments, "difficulty")
        : null;
    boolean forceCreate = Boolean.TRUE.equals(arguments.get("forceCreate"));
    String requestId = arguments.containsKey("requestId")
        ? requireString(arguments, "requestId")
        : null;

    CreateInterviewRequest request = new CreateInterviewRequest(
        resumeText,
        questionCount != null ? questionCount : DEFAULT_QUESTION_COUNT,
        resumeId,
        forceCreate,
        null,
        skillId,
        difficulty,
        null,
        null,
        requestId);
    InterviewSessionDTO session = interviewSessionService.createSession(request);
    return new ToolResponse(AgentToolName.CREATE_INTERVIEW.getName(), session);
  }

  /**
   * 提取 Long 类型参数。兼容 JSON 数字与字符串两种形态；
   * 缺失或类型无法转换时统一抛出参数错误，便于 Agent 修正后重试。
   */
  private Long requireLong(Map<String, Object> arguments, String key) {
    Object value = arguments.get(key);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text) {
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException e) {
        // 非数字字符串，走下方统一参数错误
      }
    }
    throw new BusinessException(
        ErrorCode.AGENT_TOOL_ARGUMENT_INVALID, "参数缺失或类型错误: " + key);
  }

  /** 提取非空 String 参数 */
  private String requireString(Map<String, Object> arguments, String key) {
    Object value = arguments.get(key);
    if (value instanceof String text && !text.isBlank()) {
      return text;
    }
    throw new BusinessException(
        ErrorCode.AGENT_TOOL_ARGUMENT_INVALID, "参数缺失或类型错误: " + key);
  }

  /** 提取 Integer 参数（兼容 JSON 数字与字符串两种形态） */
  private Integer requireInt(Map<String, Object> arguments, String key) {
    Object value = arguments.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text) {
      try {
        return Integer.parseInt(text);
      } catch (NumberFormatException e) {
        // 非数字字符串，走下方统一参数错误
      }
    }
    throw new BusinessException(
        ErrorCode.AGENT_TOOL_ARGUMENT_INVALID, "参数缺失或类型错误: " + key);
  }

  /**
   * 提取非空 Long 列表参数（用于多知识库检索）。
   * 列表为空或包含无法转换的元素时按参数错误处理。
   */
  private List<Long> requireLongList(Map<String, Object> arguments, String key) {
    Object value = arguments.get(key);
    if (value instanceof List<?> list && !list.isEmpty()) {
      return list.stream()
          .map(item -> {
            if (item instanceof Number number) {
              return number.longValue();
            }
            if (item instanceof String text) {
              try {
                return Long.parseLong(text);
              } catch (NumberFormatException e) {
                throw new BusinessException(
                    ErrorCode.AGENT_TOOL_ARGUMENT_INVALID, "参数类型错误: " + key);
              }
            }
            throw new BusinessException(
                ErrorCode.AGENT_TOOL_ARGUMENT_INVALID, "参数类型错误: " + key);
          })
          .toList();
    }
    throw new BusinessException(
        ErrorCode.AGENT_TOOL_ARGUMENT_INVALID, "参数缺失或类型错误: " + key);
  }
}