package interview.guide.modules.resume;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.resume.model.ResumeOptimizationProposalEntity;
import interview.guide.modules.resume.model.ResumePatchItem;
import interview.guide.modules.resume.service.ResumeOptimizationProposalService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 简历优化提案 API（P2-1）：Python 子图创建提案、Agent/前端查询回显。
 *
 * <p>创建入口供 Agent 内部链路调用；提案应用必须经用户确认
 * （apply_resume_patches CONFIRM_WRITE Tool，P2-1c）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ResumeOptimizationProposalController {

  private final ResumeOptimizationProposalService proposalService;
  private final ObjectMapper objectMapper;

  /** 创建提案（Python 优化子图生成 Patch 后调用） */
  public record CreateProposalRequest(
      Long resumeId,
      Long sourceVersionId,
      String optimizationType,
      String summary,
      List<ResumePatchItem> patches
  ) {}

  /** 提案 DTO：结构化 patches（前端 Diff 渲染直接消费） */
  public record ProposalDTO(
      Long id,
      Long resumeId,
      Long sourceVersionId,
      String optimizationType,
      String status,
      String summary,
      List<ResumePatchItem> patches,
      LocalDateTime createdAt,
      LocalDateTime decidedAt
  ) {}

  @PostMapping("/internal/agent/resume-optimization/proposals")
  public Result<Long> createProposal(@RequestBody CreateProposalRequest request) {
    ResumeOptimizationProposalEntity saved = proposalService.createProposal(
        request.resumeId(),
        request.sourceVersionId(),
        ResumeOptimizationProposalEntity.OptimizationType.valueOf(
            request.optimizationType() != null ? request.optimizationType() : "GENERAL"),
        request.summary(),
        request.patches());
    return Result.success(saved.getId());
  }

  @GetMapping("/internal/agent/resume-optimization/proposals/{proposalId}")
  public Result<ProposalDTO> getProposal(@PathVariable Long proposalId) {
    return Result.success(toDTO(proposalService.getProposal(proposalId)));
  }

  @GetMapping("/internal/agent/resumes/{resumeId}/optimization-pending")
  public Result<ProposalDTO> getLatestPending(@PathVariable Long resumeId) {
    return Result.success(toDTO(proposalService.getLatestPending(resumeId)));
  }

  private ProposalDTO toDTO(ResumeOptimizationProposalEntity entity) {
    return new ProposalDTO(
        entity.getId(),
        entity.getResumeId(),
        entity.getSourceVersionId(),
        entity.getOptimizationType() != null ? entity.getOptimizationType().name() : null,
        entity.getStatus() != null ? entity.getStatus().name() : null,
        entity.getSummary(),
        parsePatches(entity),
        entity.getCreatedAt(),
        entity.getDecidedAt());
  }

  private List<ResumePatchItem> parsePatches(ResumeOptimizationProposalEntity entity) {
    try {
      return objectMapper.readValue(entity.getPatchesJson(), new TypeReference<>() {});
    } catch (JacksonException e) {
      log.error("解析提案 Patch 列表失败: proposalId={}", entity.getId(), e);
      throw new BusinessException(ErrorCode.RESUME_OPTIMIZATION_INVALID, "提案内容损坏");
    }
  }
}
