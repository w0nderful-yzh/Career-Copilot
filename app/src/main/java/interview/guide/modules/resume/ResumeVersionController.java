package interview.guide.modules.resume;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.resume.model.ResumeContentJson;
import interview.guide.modules.resume.model.ResumeVersionDTO;
import interview.guide.modules.resume.service.ResumeVersionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
   * @param correctedContent 用户补录/修正后的内容（可空；为空则仅确认原解析）
   */
  @PostMapping("/api/resume-versions/{versionId}/confirm")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 20)
  public Result<ResumeVersionDTO> confirmVersion(
      @PathVariable Long versionId,
      @RequestBody(required = false) ResumeContentJson correctedContent) {
    ResumeVersionDTO confirmed =
        ResumeVersionDTO.from(versionService.confirmVersion(versionId, correctedContent), objectMapper);
    log.info("简历版本已确认: versionId={}, resumeId={}", versionId, confirmed.resumeId());
    return Result.success(confirmed);
  }
}
