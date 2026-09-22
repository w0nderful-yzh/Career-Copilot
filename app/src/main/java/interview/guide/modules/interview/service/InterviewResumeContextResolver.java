package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewResumeContext;
import interview.guide.modules.resume.model.ResumeContentJson;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeVersionEntity;
import interview.guide.modules.resume.service.ResumePersistenceService;
import interview.guide.modules.resume.service.ResumeVersionService;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 面试简历上下文的统一解析（P4Q-1）。
 *
 * <p>取数优先级：**明确指定的版本 → 简历的 ACTIVE 结构化版本 → 简历原文（标明来源）→ 无（通用面试）**。
 * 调用方显式传入的文本走「显式文本通道」：只在**没有** resumeId 时生效——否则两条路径各传一半，
 * 谁也说不清这场面试到底依据的是什么。
 *
 * <p>「明确指定但读不到」一律抛业务错误（带可见原因），不静默退化成通用面试：
 * 用户以为在面自己的简历，实际拿到通用题，是最糟的失败方式。
 *
 * <p>为什么优先用结构化版本：它是用户**确认过**的事实，比原始解析文本干净；
 * 原文只在没有已确认版本时使用，并如实记为 {@link InterviewResumeContext.Source#RESUME_TEXT}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewResumeContextResolver {

  /** 出题用上下文长度上限：与 Agent 侧简历注入上限同量级，防止超长原文把 prompt 撑爆 */
  private static final int MAX_CONTEXT_CHARS = 12000;

  private final ResumeVersionService resumeVersionService;
  private final ResumePersistenceService resumePersistenceService;
  private final ObjectMapper objectMapper;

  /**
   * 解析本次面试的简历上下文。
   *
   * @param resumeId      目标简历 ID（可空）
   * @param version       指定版本号（可空；仅在有 resumeId 时有意义）
   * @param explicitText  调用方直接传入的简历文本（可空；无简历 ID 的通道）
   */
  public InterviewResumeContext resolve(Long resumeId, Integer version, String explicitText) {
    if (resumeId != null) {
      return fromResume(resumeId, version);
    }
    if (explicitText != null && !explicitText.isBlank()) {
      return InterviewResumeContext.explicitText(truncate(explicitText));
    }
    return InterviewResumeContext.none();
  }

  /** 有简历 ID：先取结构化版本，不可用时降级原文；都取不到则报错（可见原因） */
  private InterviewResumeContext fromResume(Long resumeId, Integer version) {
    if (version != null) {
      // 明确指定版本：取不到就是错误，不降级——降级会让用户拿到与指定版本不符的题
      ResumeVersionEntity entity = resumeVersionService.getByResumeVersion(resumeId, version);
      return fromVersion(entity);
    }

    try {
      return fromVersion(resumeVersionService.getActiveVersion(resumeId));
    } catch (BusinessException e) {
      log.info("简历无可用结构化版本，降级使用原文: resumeId={}, reason={}", resumeId, e.getMessage());
    }

    ResumeEntity resume = resumePersistenceService.findById(resumeId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.RESUME_NOT_FOUND, "简历不存在: id=" + resumeId));
    String text = resume.getResumeText();
    if (text == null || text.isBlank()) {
      throw new BusinessException(ErrorCode.RESUME_NOT_FOUND,
          "这份简历没有可用的文本内容，请重新上传或先完成解析确认: id=" + resumeId);
    }
    return new InterviewResumeContext(
        InterviewResumeContext.Source.RESUME_TEXT, resumeId, null, truncate(text));
  }

  private InterviewResumeContext fromVersion(ResumeVersionEntity entity) {
    return new InterviewResumeContext(
        InterviewResumeContext.Source.RESUME_VERSION,
        entity.getResumeId(),
        entity.getVersion(),
        truncate(renderVersion(entity)));
  }

  /**
   * 结构化版本 → 出题用文本。
   *
   * <p>只呈现简历里**确实存在**的事实（空字段整段跳过），不补全、不猜测——
   * 出题看到什么，就应该和用户确认过的内容一致。
   */
  private String renderVersion(ResumeVersionEntity entity) {
    ResumeContentJson content = parseContent(entity);
    StringBuilder text = new StringBuilder();
    text.append("# 候选人简历（已确认结构化版本 v").append(entity.getVersion()).append("）\n");

    ResumeContentJson.BasicInfo basic = content.basicInfo();
    if (basic != null && notBlank(basic.jobIntention())) {
      appendSection(text, "求职意向", List.of(basic.jobIntention()));
    }
    appendSection(text, "教育经历", educationLines(content.education()));
    appendSection(text, "实习 / 工作经历", experienceLines(content.experience()));
    appendSection(text, "项目经历", projectLines(content.projects()));
    appendSection(text, "技能", skillLines(content.skills()));
    appendSection(text, "其他", customSectionLines(content.customSections()));
    return text.toString();
  }

  /** 结构化内容损坏属于「应该有内容却读不出来」，直接报可见错误而不是拿半份简历出题 */
  private ResumeContentJson parseContent(ResumeVersionEntity entity) {
    try {
      ResumeContentJson content =
          objectMapper.readValue(entity.getContentJson(), ResumeContentJson.class);
      return content == null ? new ResumeContentJson(null, List.of(), List.of(), List.of(), List.of(), List.of()) : content;
    } catch (JacksonException e) {
      log.error("结构化简历内容损坏，无法用于出题: resumeId={}, version={}",
          entity.getResumeId(), entity.getVersion(), e);
      throw new BusinessException(ErrorCode.INTERNAL_ERROR,
          "这份简历的结构化内容已损坏，请在简历库重新确认后再开始面试");
    }
  }

  private static List<String> educationLines(List<ResumeContentJson.EducationItem> items) {
    List<String> lines = new ArrayList<>();
    for (ResumeContentJson.EducationItem item : nullSafe(items)) {
      lines.add(join(" · ", item.school(), item.major(), item.degree(),
          dateRange(item.startDate(), item.endDate()), item.description()));
    }
    return lines;
  }

  private static List<String> experienceLines(List<ResumeContentJson.ExperienceItem> items) {
    List<String> lines = new ArrayList<>();
    for (ResumeContentJson.ExperienceItem item : nullSafe(items)) {
      lines.add(join(" · ", item.company(), item.position(),
          dateRange(item.startDate(), item.endDate())));
      lines.addAll(nullSafe(item.bullets()));
    }
    return lines;
  }

  private static List<String> projectLines(List<ResumeContentJson.ProjectItem> items) {
    List<String> lines = new ArrayList<>();
    for (ResumeContentJson.ProjectItem item : nullSafe(items)) {
      lines.add(join(" · ", item.name(), item.role(),
          dateRange(item.startDate(), item.endDate()),
          notBlank(item.techStack()) ? "技术栈：" + item.techStack() : null));
      lines.addAll(nullSafe(item.bullets()));
    }
    return lines;
  }

  private static List<String> skillLines(List<ResumeContentJson.SkillItem> items) {
    List<String> lines = new ArrayList<>();
    for (ResumeContentJson.SkillItem item : nullSafe(items)) {
      lines.add(join("：", item.category(), item.content()));
    }
    return lines;
  }

  private static List<String> customSectionLines(List<ResumeContentJson.CustomSection> sections) {
    List<String> lines = new ArrayList<>();
    for (ResumeContentJson.CustomSection section : nullSafe(sections)) {
      if (notBlank(section.title())) {
        lines.add(section.title());
      }
      lines.addAll(nullSafe(section.items()));
    }
    return lines;
  }

  /** 追加一个段落；整段为空时不输出标题（避免「## 项目经历」后面空着） */
  private static void appendSection(StringBuilder text, String title, List<String> lines) {
    List<String> present = lines.stream()
        .filter(InterviewResumeContextResolver::notBlank)
        .map(String::trim)
        .toList();
    if (present.isEmpty()) {
      return;
    }
    text.append("\n## ").append(title).append('\n');
    present.forEach(line -> text.append("- ").append(line).append('\n'));
  }

  /** 拼接非空片段（空值直接跳过，避免出现「 · · 」这类噪声） */
  private static String join(String separator, String... parts) {
    return Stream.of(parts)
        .filter(InterviewResumeContextResolver::notBlank)
        .collect(Collectors.joining(separator));
  }

  private static String dateRange(String start, String end) {
    if (notBlank(start) && notBlank(end)) {
      return start + " - " + end;
    }
    return notBlank(start) ? start : end;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static <T> List<T> nullSafe(List<T> items) {
    return items == null ? List.of() : items;
  }

  private static String truncate(String text) {
    if (text == null || text.length() <= MAX_CONTEXT_CHARS) {
      return text;
    }
    return text.substring(0, MAX_CONTEXT_CHARS);
  }
}
