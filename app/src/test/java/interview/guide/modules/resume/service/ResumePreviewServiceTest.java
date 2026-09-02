package interview.guide.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumePatchItem;
import interview.guide.modules.resume.service.ResumePreviewService.PreviewRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResumePreviewService：原版内容 + 勾选 Patch → 预览 PDF")
class ResumePreviewServiceTest {

  @Spy
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Spy
  private ResumePatchApplyService patchApplyService = org.mockito.Mockito.mock(
      ResumePatchApplyService.class);

  private final ResumePreviewService.TypstTemplateLoader templateLoader =
      new ResumePreviewService.TypstTemplateLoader();

  private TypstCompiler typstCompiler;

  @InjectMocks
  private ResumePreviewService previewService;

  private static final String SOURCE_JSON = """
      {"basicInfo": {"name": "杨子豪"}, "projects": [
        {"name": "CampusHub", "bullets": ["原有第一条描述。", "原有第二条描述。"]}
      ]}
      """;

  @BeforeEach
  void setUp() {
    // TypstCompiler 是 final 类外部依赖，直接构造 stub 注入
    typstCompiler = org.mockito.Mockito.mock(TypstCompiler.class);
    previewService = new ResumePreviewService(
        patchApplyService, typstCompiler, objectMapper, templateLoader);
  }

  private void stubPdf() {
    when(typstCompiler.compileToPdf(any(), anyString()))
        .thenReturn("%PDF-1.7 fake".getBytes(StandardCharsets.UTF_8));
  }

  private ResumePatchItem patch(String id, String type, String path, String oldValue,
      String newValue) {
    return new ResumePatchItem(id, ResumePatchItem.PatchType.valueOf(type), path, oldValue,
        newValue, "测试原因");
  }

  @Nested
  @DisplayName("内容合成")
  class MergeContent {

    @Test
    @DisplayName("无勾选 Patch：原版内容直接渲染，不调用 applyPatchesToTree")
    void noPatches() {
      stubPdf();
      previewService.renderPreview(new PreviewRequest(SOURCE_JSON, List.of(), null));

      verify(patchApplyService, never()).applyPatchesToTree(any(), any());
      verify(typstCompiler).compileToPdf(any(), anyString());
    }

    @Test
    @DisplayName("勾选 Patch：先合成后渲染，渲染内容包含修改（同一应用语义）")
    void withPatches() {
      stubPdf();
      var replace = patch("patch_1", "REPLACE", "projects[0].bullets[0]",
          "原有第一条描述。", "改写后的第一条描述。");
      previewService.renderPreview(new PreviewRequest(SOURCE_JSON, List.of(replace), null));

      // 验证传给 apply 的就是用户勾选的 patch（语义一致性由 ResumePatchApplyService 测试保证）
      verify(patchApplyService).applyPatchesToTree(any(), org.mockito.ArgumentMatchers.eq(
          List.of(replace)));
    }
  }

  @Nested
  @DisplayName("错误处理")
  class Errors {

    @Test
    @DisplayName("PATCH_CONFLICT 透传：预览期就暴露内容漂移，不等正式应用")
    void patchConflict() {
      org.mockito.Mockito.doThrow(new BusinessException(
              ErrorCode.RESUME_OPTIMIZATION_PATCH_CONFLICT, "简历内容已变化"))
          .when(patchApplyService).applyPatchesToTree(any(), any());

      var stale = patch("patch_1", "REPLACE", "projects[0].bullets[0]",
          "与原文不一致的旧值", "新值");
      assertThatThrownBy(() -> previewService.renderPreview(
          new PreviewRequest(SOURCE_JSON, List.of(stale), null)))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("已变化");
    }

    @Test
    @DisplayName("非法 JSON 内容 → 业务错误而非 Jackson 原始异常")
    void badJson() {
      assertThatThrownBy(() -> previewService.renderPreview(
          new PreviewRequest("not-json{", List.of(), null)))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("合法 JSON");
    }

    @Test
    @DisplayName("空内容 → 缺少简历内容")
    void blankContent() {
      assertThatThrownBy(() -> previewService.renderPreview(
          new PreviewRequest(" ", List.of(), null)))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("缺少简历内容");
    }
  }

  @Nested
  @DisplayName("模板加载")
  class Templates {

    @Test
    @DisplayName("默认模板 classic-zh 存在于 classpath")
    void defaultTemplateExists() {
      byte[] template = templateLoader.load("classic-zh");
      assertThat(new String(template, StandardCharsets.UTF_8)).contains("classic-zh");
    }

    @Test
    @DisplayName("白名单外模板 id → 业务错误（防任意 classpath 读取）")
    void unknownTemplateRejected() {
      assertThatThrownBy(() -> templateLoader.load("../../application.yml"))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("未知模板");
    }

    @Test
    @DisplayName("空 templateId 默认 classic-zh")
    void nullTemplateDefaults() {
      stubPdf();
      previewService.renderPreview(new PreviewRequest(SOURCE_JSON, List.of(), ""));
      verify(typstCompiler).compileToPdf(any(), anyString());
    }
  }
}
