package interview.guide.modules.job.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.job.model.JobDescriptionEntity;
import interview.guide.modules.job.service.JobDescriptionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * JD 附件 API（P2-5）：上传 / 列表 / 详情 / 删除。
 *
 * <p>前端 Composer 拖入 JD（简历/JD 类型标记）与 Copilot 会话共用；
 * Python Agent 经 internal Tool 取数（见 AgentToolService.get_job）。
 */
@Slf4j
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobDescriptionController {

  private final JobDescriptionService jobService;

  /** JD 列表项：不含全文（Token/流量纪律），详情走 /{id} */
  public record JobListItem(
      Long id, String title, String company, int contentLength, String createdAt) {}

  public record JobDetail(
      Long id, String title, String company, String contentText, String createdAt) {}

  @PostMapping("/upload")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 20)
  public Result<JobDetail> upload(@RequestParam("file") MultipartFile file) {
    JobDescriptionEntity job = jobService.upload(file);
    return Result.success(toDetail(job));
  }

  /** 文本粘贴创建（无原始文件场景） */
  public record CreateJobRequest(String title, String contentText) {}

  @PostMapping
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
  public Result<JobDetail> create(@RequestBody CreateJobRequest request) {
    JobDescriptionEntity job = jobService.createFromText(request.title(), request.contentText());
    return Result.success(toDetail(job));
  }

  @GetMapping
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 60)
  public Result<List<JobListItem>> list() {
    return Result.success(jobService.listAll().stream()
        .map(job -> new JobListItem(
            job.getId(), job.getTitle(), job.getCompany(),
            job.getContentText().length(), job.getCreatedAt().toString()))
        .toList());
  }

  @GetMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 60)
  public Result<JobDetail> get(@PathVariable Long id) {
    return Result.success(toDetail(jobService.get(id)));
  }

  @DeleteMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
  public Result<Void> delete(@PathVariable Long id) {
    jobService.delete(id);
    return Result.success();
  }

  private static JobDetail toDetail(JobDescriptionEntity job) {
    return new JobDetail(
        job.getId(), job.getTitle(), job.getCompany(), job.getContentText(),
        job.getCreatedAt().toString());
  }
}
