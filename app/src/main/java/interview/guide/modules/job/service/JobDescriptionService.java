package interview.guide.modules.job.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.job.model.JobDescriptionEntity;
import interview.guide.modules.job.repository.JobDescriptionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * JD 附件服务（P2-5）：上传（Tika 解析文本）、查询、删除。
 *
 * <p>与简历库分离：不参与简历 hash 去重语义，同文件重复上传会产生新记录
 * （JD 生命周期短、迭代频繁，按条目管理更符合使用直觉）。
 * 删除时不级联清会话绑定（与简历删除行为对称）：悬挂 active_job_id
 * 由 Python resolve_context 取数失败时兜底处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobDescriptionService {

  private final JobDescriptionRepository jobRepository;
  private final DocumentParseService documentParseService;
  private final FileStorageService fileStorageService;

  /** 上传 JD 文件：Tika 提取文本入库，原文件存 RustFS 留档（复用知识库前缀） */
  @Transactional(rollbackFor = Exception.class)
  public JobDescriptionEntity upload(MultipartFile file) {
    String filename = file.getOriginalFilename();
    String contentText = documentParseService.parseContent(file);
    if (contentText == null || contentText.isBlank()) {
      throw new BusinessException(ErrorCode.RESUME_FILE_TYPE_NOT_SUPPORTED, "JD 文件无法提取文本内容");
    }

    String fileKey = fileStorageService.uploadKnowledgeBase(file);
    JobDescriptionEntity job = new JobDescriptionEntity();
    job.setTitle(stripExtension(filename));
    job.setContentText(contentText);
    job.setFileKey(fileKey);
    JobDescriptionEntity saved = jobRepository.save(job);
    log.info("JD 已上传: id={}, title={}, 文本 {} 字符", saved.getId(), saved.getTitle(),
        contentText.length());
    return saved;
  }

  /** 直接粘贴文本创建（无原始文件） */
  @Transactional(rollbackFor = Exception.class)
  public JobDescriptionEntity createFromText(String title, String contentText) {
    if (contentText == null || contentText.isBlank()) {
      throw new BusinessException(ErrorCode.RESUME_OPTIMIZATION_INVALID, "JD 内容不能为空");
    }
    JobDescriptionEntity job = new JobDescriptionEntity();
    job.setTitle(title == null || title.isBlank() ? "未命名岗位" : title.trim());
    job.setContentText(contentText);
    JobDescriptionEntity saved = jobRepository.save(job);
    log.info("JD 已创建（文本）: id={}, title={}", saved.getId(), saved.getTitle());
    return saved;
  }

  @Transactional(readOnly = true)
  public List<JobDescriptionEntity> listAll() {
    return jobRepository.findAll();
  }

  @Transactional(readOnly = true)
  public JobDescriptionEntity get(Long id) {
    return jobRepository.findById(id)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.RESUME_NOT_FOUND, "JD 不存在: id=" + id));
  }

  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id) {
    if (jobRepository.findById(id).isEmpty()) {
      return;
    }
    jobRepository.deleteById(id);
    log.info("JD 已删除: id={}", id);
  }

  private static String stripExtension(String filename) {
    if (filename == null || filename.isBlank()) {
      return "未命名岗位";
    }
    return filename.replaceAll("\\.[^.]+$", "");
  }
}
