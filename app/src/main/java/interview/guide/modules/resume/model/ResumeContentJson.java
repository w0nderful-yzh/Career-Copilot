package interview.guide.modules.resume.model;

import java.util.List;

/**
 * 结构化 Resume JSON（P2-0）：解析、Patch、模板渲染共用的统一数据结构。
 *
 * <p>解析原则「不猜测」：LLM 无法从原文提取的字段置 null，由解析服务汇总到
 * missingFields 清单引导用户补录；绝不编造内容。
 *
 * <p>customSections 兜底段：证书/奖项/链接等非标准段完整保留，防静默丢内容。
 */
public record ResumeContentJson(
    BasicInfo basicInfo,
    List<EducationItem> education,
    List<ExperienceItem> experience,
    List<ProjectItem> projects,
    List<SkillItem> skills,
    List<CustomSection> customSections
) {

  /** 个人基本信息（求职意向可选，原文没有则不填） */
  public record BasicInfo(
      String name,
      String phone,
      String email,
      String location,
      String jobIntention
  ) {}

  /** 教育经历 */
  public record EducationItem(
      String school,
      String major,
      String degree,       // 学历（本科/硕士/…；原文没有则 null）
      String startDate,    // YYYY-MM 或 YYYY.MM，保持原文格式
      String endDate,
      String description   // GPA / 排名 / 主修课程等（可选）
  ) {}

  /** 工作/实习经历 */
  public record ExperienceItem(
      String company,
      String position,
      String startDate,
      String endDate,
      List<String> bullets
  ) {}

  /** 项目经历 */
  public record ProjectItem(
      String name,
      String role,         // 担任角色（可选）
      String startDate,
      String endDate,
      String techStack,    // 技术栈（可选，逗号分隔保持原文）
      List<String> bullets
  ) {}

  /** 技能条目 */
  public record SkillItem(
      String category,     // 分类（如 语言/框架/工具；原文无分类则 null）
      String content       // 技能内容（保持原文的熟练度表述，不改写）
  ) {}

  /** 非标准段兜底（证书/奖项/自我评价/社区链接等，防静默丢失） */
  public record CustomSection(
      String title,
      List<String> items
  ) {}
}
