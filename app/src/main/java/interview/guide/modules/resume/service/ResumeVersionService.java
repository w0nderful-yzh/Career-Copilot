package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumeContentJson;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeVersionEntity;
import interview.guide.modules.resume.repository.ResumeVersionRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 简历版本服务（P2-0）：原始导入版本（V1）的生命周期管理。
 *
 * <p>V1 在评分分析成功后自动创建（source=IMPORT），解析结果待用户确认；
 * 用户确认后状态转 ACTIVE，才可作为 P2-1 优化子图的取数基础。
 * AI 优化产物的版本创建在 P2-2 接入（source=AI_OPTIMIZE）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeVersionService {

  private final ResumeVersionRepository versionRepository;
  private final ResumePersistenceService persistenceService;
  private final ObjectMapper objectMapper;
  private final ResumePreviewService.TypstTemplateLoader templateLoader;
  private final TypstCompiler typstCompiler;
  private final interview.guide.infrastructure.file.FileStorageService fileStorageService;

  /**
   * 创建原始导入版本（V1）：评分分析成功后由异步链路调用。
   *
   * <p>幂等：已存在 V1 时不重复创建（重复消费 / 重分析场景）。
   *
   * @return 创建的版本；已存在时返回既有 V1
   */
  @Transactional(rollbackFor = Exception.class)
  public ResumeVersionEntity createImportVersion(Long resumeId, ResumeParseStructuredService.ResumeParseResult parseResult) {
    if (persistenceService.findById(resumeId).isEmpty()) {
      // 异步处理前校验实体存在；已删除直接丢弃任务
      throw new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在: id=" + resumeId);
    }

    var existing = versionRepository.findByResumeIdAndVersion(resumeId, 1);
    if (existing.isPresent()) {
      log.info("导入版本已存在，跳过重复创建: resumeId={}", resumeId);
      return existing.get();
    }

    ResumeVersionEntity version = new ResumeVersionEntity();
    version.setResumeId(resumeId);
    version.setVersion(1);
    version.setSource(ResumeVersionEntity.VersionSource.IMPORT);
    version.setContentJson(serialize(parseResult.content()));
    version.setSourceCreatedAt(LocalDateTime.now());

    // 关键字段缺失（如姓名）→ NEED_USER_INFO；否则待确认
    if (parseResult.hasCriticalMissing()) {
      version.setConfirmationStatus(ResumeVersionEntity.ConfirmationStatus.NEED_USER_INFO);
    } else {
      version.setConfirmationStatus(ResumeVersionEntity.ConfirmationStatus.PENDING_CONFIRMATION);
    }
    version.setMissingFieldsJson(serializeIfNotEmpty(parseResult.missingFields()));

    ResumeVersionEntity saved = versionRepository.save(version);
    log.info("简历导入版本已创建: resumeId={}, versionId={}, status={}, 缺失字段={}",
        resumeId, saved.getId(), saved.getConfirmationStatus(), parseResult.missingFields());
    return saved;
  }

  /**
   * 用户确认解析结果：PENDING_CONFIRMATION / NEED_USER_INFO → ACTIVE。
   *
   * <p>确认后该版本才可作为优化基础。补录内容的合并编辑（前端表单回填）
   * 由确认请求携带修正后的 contentJson 一起提交。
   */
  @Transactional(rollbackFor = Exception.class)
  public ResumeVersionEntity confirmVersion(Long versionId, ResumeContentJson correctedContent) {
    ResumeVersionEntity version = versionRepository.findById(versionId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.RESUME_VERSION_NOT_FOUND, "简历版本不存在: id=" + versionId));

    if (correctedContent != null) {
      // 用户补录/修正后确认：内容以修正版为准，缺失清单清空
      version.setContentJson(serialize(correctedContent));
      version.setMissingFieldsJson(null);
    }
    version.setConfirmationStatus(ResumeVersionEntity.ConfirmationStatus.ACTIVE);
    return versionRepository.save(version);
  }

  /** 简历的全部版本（新在前） */
  @Transactional(readOnly = true)
  public List<ResumeVersionEntity> listVersions(Long resumeId) {
    return versionRepository.findByResumeIdOrderByVersionDesc(resumeId);
  }

  /** 按版本 ID 取版本 */
  @Transactional(readOnly = true)
  public ResumeVersionEntity getVersion(Long versionId) {
    return versionRepository.findById(versionId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.RESUME_VERSION_NOT_FOUND, "简历版本不存在: id=" + versionId));
  }

  /** 简历的最新 ACTIVE 版本（优化子图取数入口；无 ACTIVE 返回空） */
  @Transactional(readOnly = true)
  public ResumeVersionEntity getActiveVersion(Long resumeId) {
    return versionRepository.findByResumeIdOrderByVersionDesc(resumeId).stream()
        .filter(v -> v.getConfirmationStatus() == ResumeVersionEntity.ConfirmationStatus.ACTIVE)
        .findFirst()
        .orElseThrow(() -> new BusinessException(
            ErrorCode.RESUME_VERSION_NOT_READY,
            "简历还没有已确认的结构化版本，请先在简历库完成解析确认"));
  }

  /** 按版本号精确定位（Agent Tool 带版本号取数时用） */
  @Transactional(readOnly = true)
  public ResumeVersionEntity getByResumeVersion(Long resumeId, Integer version) {
    return versionRepository.findByResumeIdAndVersion(resumeId, version)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.RESUME_VERSION_NOT_FOUND,
            "简历版本不存在: resumeId=" + resumeId + ", version=" + version));
  }

  /**
   * 正式导出 PDF（P2-4）：版本 content_json → Typst 渲染 → RustFS。
   *
   * <p>手动导出（设计决策：不自动渲染，避免用户不导出时的浪费渲染）。
   * 渲染与上传不在同一事务（外部 IO 不进 DB 事务）；导出产物幂等性无要求，
   * 重复导出生成新 fileKey 并返回（不覆盖旧文件）。
   */
  public VersionPdfExport exportVersionPdf(Long versionId) {
    ResumeVersionEntity version = getVersion(versionId);
    if (version.getContentJson() == null || version.getContentJson().isBlank()) {
      throw new BusinessException(ErrorCode.RESUME_VERSION_NOT_READY, "版本没有结构化内容，无法导出");
    }

    byte[] template = templateLoader.load("classic-zh");
    byte[] pdf = typstCompiler.compileToPdf(template, version.getContentJson());

    String filename = exportFilename(version);
    String fileKey = fileStorageService.uploadBytes(
        pdf, filename, "application/pdf", "resume-exports");
    log.info("简历版本 PDF 已导出: versionId={}, fileKey={} ({} bytes)",
        versionId, fileKey, pdf.length);
    return new VersionPdfExport(fileKey, fileStorageService.getFileUrl(fileKey), filename,
        pdf.length);
  }

  /** 导出文件名：简历名_v{版本号}.pdf（存储键走 generateFileKey 去重） */
  private String exportFilename(ResumeVersionEntity version) {
    ResumeEntity resume = persistenceService.findById(version.getResumeId())
        .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
    String baseName = resume.getOriginalFilename() != null
        ? resume.getOriginalFilename().replaceAll("\\.[^.]+$", "") : "resume";
    return baseName + "_v" + version.getVersion() + ".pdf";
  }

  /** 版本 PDF 导出结果 */
  public record VersionPdfExport(String fileKey, String url, String filename, long sizeBytes) {}

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException e) {
      log.error("序列化简历版本内容失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "保存解析结果失败");
    }
  }

  private String serializeIfNotEmpty(List<String> values) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(values);
    } catch (JacksonException e) {
      log.warn("序列化缺失字段清单失败: {}", e.getMessage());
      return null;
    }
  }
}
