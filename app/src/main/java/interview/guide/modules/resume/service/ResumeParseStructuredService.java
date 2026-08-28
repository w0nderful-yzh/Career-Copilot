package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumeContentJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * 简历结构化解析服务（P2-0）：raw_text → LLM → Resume JSON。
 *
 * <p>解析原则「不猜测」：LLM 按提示词约定把无法提取的字段置 null，
 * 本服务遍历结果汇总缺失字段清单（missingFields），供前端补录引导与
 * NEED_USER_INFO 状态判定。关键身份字段（姓名）缺失视为解析失败。
 */
@Service
public class ResumeParseStructuredService {

  private static final Logger log = LoggerFactory.getLogger(ResumeParseStructuredService.class);

  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final PromptTemplate systemPromptTemplate;
  private final PromptTemplate userPromptTemplate;
  private final BeanOutputConverter<ResumeContentJson> outputConverter;

  public ResumeParseStructuredService(
      LlmProviderRegistry llmProviderRegistry,
      StructuredOutputInvoker structuredOutputInvoker,
      ResumeAnalysisProperties properties,
      ResourceLoader resourceLoader
  ) throws IOException {
    this.llmProviderRegistry = llmProviderRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.systemPromptTemplate = new PromptTemplate(
        resourceLoader.getResource("classpath:prompts/resume-parse-system.st")
            .getContentAsString(StandardCharsets.UTF_8));
    this.userPromptTemplate = new PromptTemplate(
        resourceLoader.getResource("classpath:prompts/resume-parse-user.st")
            .getContentAsString(StandardCharsets.UTF_8));
    this.outputConverter = new BeanOutputConverter<>(ResumeContentJson.class);
  }

  /**
   * 结构化解析简历文本。
   *
   * @return 解析结果 + 缺失字段清单（business 日志用，持久化由调用方负责）
   */
  public ResumeParseResult parse(String resumeText) {
    try {
      ChatClient chatClient = llmProviderRegistry.getDefaultChatClient();
      String systemPrompt = systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
      String userPrompt = userPromptTemplate.render(Map.of("resumeText", resumeText));

      ResumeContentJson content = structuredOutputInvoker.invoke(
          chatClient,
          systemPrompt,
          userPrompt,
          outputConverter,
          ErrorCode.RESUME_PARSE_FAILED,
          "简历结构化解析失败：",
          "简历结构化解析",
          log
      );

      // 防御性归一化：LLM 偶发返回 null 字段（schema 要求 string），统一转为空串
      content = normalizeNulls(content);

      List<String> missingFields = collectMissingFields(content);
      log.info("简历结构化解析完成: 项目数={}, 技能数={}, 缺失字段={}",
          content.projects() == null ? 0 : content.projects().size(),
          content.skills() == null ? 0 : content.skills().size(),
          missingFields);
      return new ResumeParseResult(content, missingFields);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("简历结构化解析异常: {}", e.getMessage(), e);
      throw new BusinessException(
          ErrorCode.RESUME_PARSE_FAILED, "简历结构化解析失败：" + e.getMessage());
    }
  }

  /**
   * 汇总解析缺失字段：不猜测原则下 LLM 置空（"" 或 null）的字段逐项记录。
   * 姓名缺失属解析失败（关键身份字段），由调用方按失败处理。
   */
  private List<String> collectMissingFields(ResumeContentJson content) {
    List<String> missing = new ArrayList<>();
    ResumeContentJson.BasicInfo basic = content.basicInfo();
    if (basic == null) {
      missing.add("basicInfo");
    } else {
      if (isBlank(basic.name())) {
        missing.add("basicInfo.name");
      }
      if (isBlank(basic.phone()) && isBlank(basic.email())) {
        missing.add("basicInfo.contact（电话与邮箱均缺失）");
      }
    }
    if (content.education() == null || content.education().isEmpty()) {
      missing.add("education");
    }
    if ((content.experience() == null || content.experience().isEmpty())
        && (content.projects() == null || content.projects().isEmpty())) {
      missing.add("experience/projects（经历与项目均为空，请确认解析是否遗漏）");
    }
    return missing;
  }

  /**
   * 归一化 LLM 输出中的 null 字段为空串：schema 声明字段为 required string，
   * 模型偶发违反约定返回 null；缺失语义统一用空串表达（「不猜测」原则）。
   */
  private ResumeContentJson normalizeNulls(ResumeContentJson content) {
    ResumeContentJson.BasicInfo rawBasic = content.basicInfo();
    ResumeContentJson.BasicInfo basic = rawBasic == null ? null : new ResumeContentJson.BasicInfo(
        orEmpty(rawBasic.name()), orEmpty(rawBasic.phone()), orEmpty(rawBasic.email()),
        orEmpty(rawBasic.location()), orEmpty(rawBasic.jobIntention()));

    List<ResumeContentJson.EducationItem> education = normalizeList(
        content.education(),
        item -> new ResumeContentJson.EducationItem(
            orEmpty(item.school()), orEmpty(item.major()), orEmpty(item.degree()),
            orEmpty(item.startDate()), orEmpty(item.endDate()), orEmpty(item.description())));
    List<ResumeContentJson.ExperienceItem> experience = normalizeList(
        content.experience(),
        item -> new ResumeContentJson.ExperienceItem(
            orEmpty(item.company()), orEmpty(item.position()),
            orEmpty(item.startDate()), orEmpty(item.endDate()),
            orEmptyList(item.bullets())));
    List<ResumeContentJson.ProjectItem> projects = normalizeList(
        content.projects(),
        item -> new ResumeContentJson.ProjectItem(
            orEmpty(item.name()), orEmpty(item.role()),
            orEmpty(item.startDate()), orEmpty(item.endDate()), orEmpty(item.techStack()),
            orEmptyList(item.bullets())));
    List<ResumeContentJson.SkillItem> skills = normalizeList(
        content.skills(),
        item -> new ResumeContentJson.SkillItem(orEmpty(item.category()), orEmpty(item.content())));
    List<ResumeContentJson.CustomSection> customSections = normalizeList(
        content.customSections(),
        item -> new ResumeContentJson.CustomSection(
            orEmpty(item.title()), orEmptyList(item.items())));

    return new ResumeContentJson(basic, education, experience, projects, skills, customSections);
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static List<String> orEmptyList(List<String> value) {
    return value == null ? List.of() : value;
  }

  private <T, R> List<R> normalizeList(List<T> items, java.util.function.Function<T, R> mapper) {
    if (items == null) {
      return List.of();
    }
    // 单条记录部分字段为 null 时同样归一（List 元素本身不会是 null，防御性过滤）
    return items.stream().filter(java.util.Objects::nonNull).map(mapper).toList();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** 解析结果：结构化内容 + 缺失字段清单 */
  public record ResumeParseResult(
      ResumeContentJson content,
      List<String> missingFields
  ) {

    /** 关键字段（姓名）缺失即视为解析不完整，需用户补录后确认 */
    public boolean hasCriticalMissing() {
      return missingFields.stream().anyMatch(f -> f.equals("basicInfo") || f.equals("basicInfo.name"));
    }
  }
}
