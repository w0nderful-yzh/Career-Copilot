package interview.guide.modules.resume;

import interview.guide.modules.resume.model.ResumePatchItem;
import interview.guide.modules.resume.service.ResumePreviewService;
import interview.guide.modules.resume.service.ResumePreviewService.PreviewRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 简历 Preview PDF 端点（P2-4，Agent 内部接口）。
 *
 * <p>原版内容 + 已勾选 Patch → 内存合成 → Typst 渲染 → PDF 字节直返。
 * 不入库不落存储（Preview ≠ 正式版本）；前端勾选变化防抖调用本端点刷新 iframe。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ResumePreviewController {

  private final ResumePreviewService previewService;

  /** 请求体：原版内容 JSON + 勾选的 Patch（可为空 = 渲染原版）+ 模板 id（可空默认 classic-zh） */
  public record PreviewBody(String contentJson, List<ResumePatchItem> patches,
      String templateId) {}

  @PostMapping(value = "/internal/agent/resume/preview", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> preview(@org.springframework.web.bind.annotation.RequestBody
      PreviewBody body) {
    long startAt = System.currentTimeMillis();
    byte[] pdf = previewService.renderPreview(new PreviewRequest(
        body.contentJson(), body.patches() == null ? List.of() : body.patches(),
        body.templateId()));
    log.info("Preview PDF 渲染完成: patches={}, {} bytes, {}ms",
        body.patches() == null ? 0 : body.patches().size(), pdf.length,
        System.currentTimeMillis() - startAt);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf);
  }
}
