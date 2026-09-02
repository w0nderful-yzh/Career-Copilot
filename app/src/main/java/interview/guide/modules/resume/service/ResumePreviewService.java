package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumePatchItem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Preview PDF 服务（P2-4）：把原版内容 + 已勾选 Patch 合成为预览 PDF 字节。
 *
 * <p>Preview ≠ 正式版本：不入库、不落存储、零持久化（设计决策「勾选即重渲」，
 * 确认前临时 JSON 直渲 PDF 字节）。应用语义与正式应用共用 applyPatchesToTree，
 * 保证「预览内容 = 应用后内容」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePreviewService {

  private final ResumePatchApplyService patchApplyService;
  private final TypstCompiler typstCompiler;
  private final ObjectMapper objectMapper;
  private final TypstTemplateLoader templateLoader;

  /** 预览请求：原版内容 + 用户已勾选的 Patch + 模板 id */
  public record PreviewRequest(String contentJson, List<ResumePatchItem> patches,
      String templateId) {}

  /**
   * 渲染预览 PDF。
   *
   * @return PDF 字节（直接写响应，不落任何存储）
   */
  public byte[] renderPreview(PreviewRequest request) {
    if (request.contentJson() == null || request.contentJson().isBlank()) {
      throw new BusinessException(ErrorCode.RESUME_OPTIMIZATION_INVALID, "缺少简历内容");
    }
    String templateId = (request.templateId() == null || request.templateId().isBlank())
        ? "classic-zh" : request.templateId();

    JsonNode content = parseContent(request.contentJson());
    List<ResumePatchItem> patches = request.patches() == null ? List.of() : request.patches();
    if (!patches.isEmpty()) {
      // 与正式应用同一套校验与应用语义（oldValue 一致性 / path 白名单 / REORDER 拒绝）
      patchApplyService.applyPatchesToTree(content, patches);
    }

    String mergedJson;
    try {
      mergedJson = objectMapper.writeValueAsString(content);
    } catch (JacksonException e) {
      log.error("预览内容序列化失败", e);
      throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "预览内容异常");
    }

    byte[] template = templateLoader.load(templateId);
    return typstCompiler.compileToPdf(template, mergedJson);
  }

  private JsonNode parseContent(String contentJson) {
    try {
      JsonNode node = objectMapper.readTree(contentJson);
      if (!(node instanceof ObjectNode)) {
        throw new BusinessException(ErrorCode.RESUME_OPTIMIZATION_INVALID, "简历内容格式异常");
      }
      return node;
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.RESUME_OPTIMIZATION_INVALID, "简历内容不是合法 JSON");
    }
  }

  /** 模板加载：classpath 内置模板（一期只有 classic-zh） */
  @Slf4j
  @org.springframework.stereotype.Component
  public static class TypstTemplateLoader {

    private static final String TEMPLATE_PATH_FORMAT = "/typst/resume-%s.typ";

    /** 合法模板白名单：防止任意 classpath 资源读取 */
    private static final java.util.Set<String> KNOWN_TEMPLATES = java.util.Set.of("classic-zh");

    public byte[] load(String templateId) {
      if (!KNOWN_TEMPLATES.contains(templateId)) {
        throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "未知模板: " + templateId);
      }
      String path = TEMPLATE_PATH_FORMAT.formatted(templateId);
      try (var in = getClass().getResourceAsStream(path)) {
        if (in == null) {
          log.error("模板资源缺失: {}", path);
          throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "模板缺失，请联系管理员");
        }
        return in.readAllBytes();
      } catch (IOException e) {
        log.error("模板读取失败: {}", path, e);
        throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "模板读取失败");
      } catch (UncheckedIOException e) {
        throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "模板读取失败");
      }
    }
  }
}
