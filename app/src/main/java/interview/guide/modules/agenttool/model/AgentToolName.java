package interview.guide.modules.agenttool.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Agent Tool 注册表。
 *
 * <p>定义 Agent 可调用的业务能力：Tool 名称（snake_case，对 LLM 友好）、
 * 用途描述与权限等级。权限等级由代码明确决定，不依赖 Prompt。
 */
public enum AgentToolName {

  GET_RESUME_LIST(
      "get_resume_list",
      "获取简历列表，包含最新分析分数与面试次数",
      AgentToolPermission.READ),
  GET_SKILL_PROFILE(
      "get_skill_profile",
      "获取用户技能画像（各技能聚合分与可追溯证据：来自哪些面试、得了多少分）",
      AgentToolPermission.READ),
  GET_RESUME_VERSION(
      "get_resume_version",
      "获取简历的结构化版本内容（基本信息/教育/经历/项目/技能 JSON，简历优化取数用）",
      AgentToolPermission.READ),
  GET_RESUME_ANALYSIS(
      "get_resume_analysis",
      "获取指定简历的最新分析结果（各项评分、优势与改进建议）",
      AgentToolPermission.READ),
  GET_RESUME(
      "get_resume",
      "获取指定简历的完整内容（解析文本）与元信息",
      AgentToolPermission.READ),
  GET_JOB(
      "get_job",
      "获取指定 JD（岗位描述）的完整内容与元信息（jobId，简历优化 JD 定向模式 / JD 匹配取数用）",
      AgentToolPermission.READ),
  GET_INTERVIEW_HISTORY(
      "get_interview_history",
      "获取模拟面试历史列表，可按 resumeId 过滤",
      AgentToolPermission.READ),
  GET_INTERVIEW_REPORT(
      "get_interview_report",
      "获取单场模拟面试的完整报告（sessionId）",
      AgentToolPermission.READ),
  LIST_KNOWLEDGE_BASES(
      "list_knowledge_bases",
      "获取知识库列表",
      AgentToolPermission.READ),
  SEARCH_KNOWLEDGE(
      "search_knowledge",
      "基于 RAG 知识库回答技术问题（knowledgeBaseIds + question）",
      AgentToolPermission.READ),
  LIST_SKILLS(
      "list_skills",
      "获取可用的模拟面试技能方向列表",
      AgentToolPermission.READ),
  CREATE_INTERVIEW(
      "create_interview",
      "创建模拟面试会话（mode/direction/difficulty/focus，需用户确认后执行）",
      AgentToolPermission.CONFIRM_WRITE),
  APPLY_RESUME_PATCHES(
      "apply_resume_patches",
      "应用用户确认的简历优化建议并生成新版本（proposalId + 勾选的 patchIds，需用户确认后执行）",
      AgentToolPermission.CONFIRM_WRITE);

  private final String name;
  private final String description;
  private final AgentToolPermission permission;

  AgentToolName(String name, String description, AgentToolPermission permission) {
    this.name = name;
    this.description = description;
    this.permission = permission;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public AgentToolPermission getPermission() {
    return permission;
  }

  /** 按 Tool 名称（snake_case）解析枚举，未知名称返回 empty */
  public static Optional<AgentToolName> from(String name) {
    return Arrays.stream(values())
        .filter(tool -> tool.name.equals(name))
        .findFirst();
  }
}