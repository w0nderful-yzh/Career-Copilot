package interview.guide.modules.resume;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.resume.model.ResumeContentJson;
import interview.guide.modules.resume.model.ResumeVersionDTO;
import interview.guide.modules.resume.service.ResumeVersionService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * 简历版本 API（P2-0）：解析结果查询与用户确认。
 *
 * <p>解析结果需用户确认后才可作为简历优化的取数基础；
 * 确认请求可携带修正后的结构化内容（补录缺失字段）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ResumeVersionController {

  private final ResumeVersionService versionService;
  private final ObjectMapper objectMapper;
  private final FileStorageService fileStorageService;

  /** 指定简历的全部版本（含解析确认状态，前端简历库详情页与 Copilot 共用） */
  @GetMapping("/api/resumes/{resumeId}/versions")
  public Result<List<ResumeVersionDTO>> listVersions(@PathVariable Long resumeId) {
    return Result.success(versionService.listVersions(resumeId).stream()
        .map(version -> ResumeVersionDTO.from(version, objectMapper))
        .toList());
  }

  /** 单个版本详情（结构化内容） */
  @GetMapping("/api/resume-versions/{versionId}")
  public Result<ResumeVersionDTO> getVersion(@PathVariable Long versionId) {
    return Result.success(ResumeVersionDTO.from(versionService.getVersion(versionId), objectMapper));
  }

  /**
   * 确认解析结果：状态 → ACTIVE。
   *
   * <p>请求体可为 null（仅确认）或 {correctedContent: {...}}（补录修正后确认）。
   * 注意不能用裸 ResumeContentJson 作 body：空 JSON 对象会被反序列化为
   * 全 null 字段对象，与「未提供」无法区分，会误覆盖已解析内容。
   */
  public record ConfirmVersionRequest(ResumeContentJson correctedContent) {}

  @PostMapping("/api/resume-versions/{versionId}/confirm")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 20)
  public Result<ResumeVersionDTO> confirmVersion(
      @PathVariable Long versionId,
      @RequestBody(required = false) ConfirmVersionRequest request) {
    ResumeContentJson corrected = request != null ? request.correctedContent() : null;
    ResumeVersionDTO confirmed =
        ResumeVersionDTO.from(versionService.confirmVersion(versionId, corrected), objectMapper);
    log.info("简历版本已确认: versionId={}, resumeId={}", versionId, confirmed.resumeId());
    return Result.success(confirmed);
  }

  /**
   * 正式导出版本 PDF（P2-4，手动导出）：渲染 → RustFS → 返回下载信息。
   * 不自动渲染（设计决策，避免浪费渲染）。
   */
  public record VersionPdfExportResponse(
      String fileKey, String url, String filename, long sizeBytes) {}

  @PostMapping("/api/resume-versions/{versionId}/export-pdf")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
  public Result<VersionPdfExportResponse> exportPdf(@PathVariable Long versionId) {
    var export = versionService.exportVersionPdf(versionId);
    return Result.success(new VersionPdfExportResponse(
        export.fileKey(), export.url(), export.filename(), export.sizeBytes()));
  }

  /**
   * 导出 PDF 下载代理：RustFS bucket 非 public-read（与既有简历文件一致），
   * 直链 URL 浏览器拿不到文件，经后端流式代理（复用面试报告导出的字节直返模式）。
   * fileKey 限定 resume-exports/ 前缀，防止任意对象读取。
   */
  @GetMapping("/api/resume-exports/download")
  public ResponseEntity<byte[]> downloadExport(@RequestParam String fileKey) {
    if (fileKey == null || !fileKey.startsWith("resume-exports/")) {
      throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "非法的下载路径");
    }
    byte[] pdf = fileStorageService.downloadFile(fileKey);
    String encodedName = URLEncoder.encode(
        fileKey.substring(fileKey.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename*=UTF-8''" + encodedName)
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf);
  }
}
