package interview.guide.modules.resume;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.resume.model.ResumeOptimizationProposalEntity;
import interview.guide.modules.resume.model.ResumeJdGapAnalysis;
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
      Long targetJobId,
      String targetDirection,
      String summary,
      ResumeJdGapAnalysis jdGapAnalysis,
      List<ResumePatchItem> patches
  ) {}

  /** 提案 DTO：结构化 patches（前端 Diff 渲染直接消费） */
  public record ProposalDTO(
      Long id,
      Long resumeId,
      Long sourceVersionId,
      String optimizationType,
      Long targetJobId,
      String targetDirection,
      String status,
      String summary,
      ResumeJdGapAnalysis jdGapAnalysis,
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
        request.targetJobId(),
        request.targetDirection(),
        request.summary(),
        request.jdGapAnalysis(),
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

  /**
   * 提案状态回显（前端块加载/刷新回放历史消息时用）。
   *
   * <p>历史消息里的 ResumeOptimizationBlock 只存了 patches，不带决策状态；
   * 不回显的话，已应用/已忽略的提案在刷新后仍显示成可再次操作。
   */
  @GetMapping("/api/resume-optimization/proposals/{proposalId}")
  public Result<ProposalDTO> getProposalForUser(@PathVariable Long proposalId) {
    return Result.success(toDTO(proposalService.getProposal(proposalId)));
  }

  /**
   * 放弃本轮全部建议：PENDING → REJECTED（与 apply 对称的用户决策出口）。
   *
   * <p>拒绝不改动任何简历内容，只落审计状态（decidedAt + REJECTED）；
   * 重复拒绝由状态机拒绝（提案已处理）。
   */
  @PostMapping("/api/resume-optimization/proposals/{proposalId}/reject")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 20)
  public Result<ProposalDTO> rejectProposal(@PathVariable Long proposalId) {
    ResumeOptimizationProposalEntity rejected = proposalService.transitionFromPending(
        proposalId, ResumeOptimizationProposalEntity.ProposalStatus.REJECTED);
    log.info("简历优化提案被用户拒绝: proposalId={}, resumeId={}",
        proposalId, rejected.getResumeId());
    return Result.success(toDTO(rejected));
  }

  private ProposalDTO toDTO(ResumeOptimizationProposalEntity entity) {
    return new ProposalDTO(
        entity.getId(),
        entity.getResumeId(),
        entity.getSourceVersionId(),
        entity.getOptimizationType() != null ? entity.getOptimizationType().name() : null,
        entity.getTargetJobId(),
        entity.getTargetDirection(),
        entity.getStatus() != null ? entity.getStatus().name() : null,
        entity.getSummary(),
        proposalService.parseJdGapAnalysis(entity),
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
