package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumeOptimizationProposalEntity;
import interview.guide.modules.resume.model.ResumeJdGapAnalysis;
import interview.guide.modules.resume.model.ResumePatchItem;
import interview.guide.modules.resume.repository.ResumeOptimizationProposalRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 简历优化提案服务（P2-1）：提案的持久化、查询与状态流转。
 *
 * <p>创建入口由 Python 优化子图经内部端点调用（提案先落库，
 * 再返回提案 id 供前端 ACTION_SELECTED 回传应用）；应用/拒绝在
 * {@link ResumePatchApplyService}（P2-1c）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeOptimizationProposalService {

  private final ResumeOptimizationProposalRepository proposalRepository;
  private final ObjectMapper objectMapper;

  /** 兼容非 JD 模式与既有调用点。 */
  public ResumeOptimizationProposalEntity createProposal(
      Long resumeId,
      Long sourceVersionId,
      ResumeOptimizationProposalEntity.OptimizationType optimizationType,
      Long targetJobId,
      String targetDirection,
      String summary,
      List<ResumePatchItem> patches) {
    return createProposal(
        resumeId, sourceVersionId, optimizationType, targetJobId, targetDirection,
        summary, null, patches);
  }

  /**
   * 创建提案（Python 子图调用）。
   *
   * @param optimizationType 优化模式（GENERAL / TARGET_DIRECTION / JD_TARGETED）
   * @param targetJobId      JD_TARGETED 时的目标 JD（其余模式为 null）
   * @param targetDirection  TARGET_DIRECTION 时的方向描述（其余模式为 null）
   * @param patches          Patch 列表（结构已由 Python 校验器把关；此处只做基本校验）
   * @return 已持久化的提案（含 id）
   */
  @Transactional(rollbackFor = Exception.class)
  public ResumeOptimizationProposalEntity createProposal(
      Long resumeId,
      Long sourceVersionId,
      ResumeOptimizationProposalEntity.OptimizationType optimizationType,
      Long targetJobId,
      String targetDirection,
      String summary,
      ResumeJdGapAnalysis jdGapAnalysis,
      List<ResumePatchItem> patches) {
    if (optimizationType == ResumeOptimizationProposalEntity.OptimizationType.JD_TARGETED
        && (targetJobId == null
            || jdGapAnalysis == null
            || jdGapAnalysis.items() == null
            || jdGapAnalysis.items().isEmpty())) {
      throw new BusinessException(
          ErrorCode.RESUME_OPTIMIZATION_INVALID,
          "JD 定向提案必须包含目标 JD 与非空 Gap 分析");
    }
    if (patches == null || patches.isEmpty()) {
      throw new BusinessException(ErrorCode.RESUME_OPTIMIZATION_INVALID, "提案不包含任何修改建议");
    }
    // REORDER 一期不支持：Python 校验器已拒绝，此处兜底（防御直连端点）
    boolean hasReorder = patches.stream()
        .anyMatch(p -> p.type() == ResumePatchItem.PatchType.REORDER);
    if (hasReorder) {
      throw new BusinessException(ErrorCode.RESUME_OPTIMIZATION_INVALID, "暂不支持 REORDER 类型的修改建议");
    }

    ResumeOptimizationProposalEntity proposal = new ResumeOptimizationProposalEntity();
    proposal.setResumeId(resumeId);
    proposal.setSourceVersionId(sourceVersionId);
    proposal.setOptimizationType(optimizationType);
    proposal.setTargetJobId(targetJobId);
    proposal.setTargetDirection(targetDirection);
    proposal.setStatus(ResumeOptimizationProposalEntity.ProposalStatus.PENDING);
    proposal.setSummary(summary);
    proposal.setJdGapAnalysisJson(serializeNullable(jdGapAnalysis));
    proposal.setPatchesJson(serialize(patches));

    ResumeOptimizationProposalEntity saved = proposalRepository.save(proposal);
    log.info("简历优化提案已创建: proposalId={}, resumeId={}, mode={}, targetJobId={}, patches={}",
        saved.getId(), resumeId, optimizationType, targetJobId, patches.size());
    return saved;
  }

  /** 按 id 取提案；不存在报业务错误 */
  @Transactional(readOnly = true)
  public ResumeOptimizationProposalEntity getProposal(Long proposalId) {
    return proposalRepository.findById(proposalId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.RESUME_OPTIMIZATION_PROPOSAL_NOT_FOUND, "优化提案不存在: id=" + proposalId));
  }

  /** 解析提案的 Patch 列表 */
  @Transactional(readOnly = true)
  public List<ResumePatchItem> parsePatches(ResumeOptimizationProposalEntity proposal) {
    try {
      return objectMapper.readValue(proposal.getPatchesJson(), new TypeReference<>() {});
    } catch (JacksonException e) {
      log.error("解析提案 Patch 列表失败: proposalId={}", proposal.getId(), e);
      throw new BusinessException(
          ErrorCode.RESUME_OPTIMIZATION_INVALID, "提案内容损坏，无法应用");
    }
  }

  /** 还原 JD Gap 快照；通用 / 方向优化没有 Gap 时返回 null。 */
  @Transactional(readOnly = true)
  public ResumeJdGapAnalysis parseJdGapAnalysis(
      ResumeOptimizationProposalEntity proposal) {
    String json = proposal.getJdGapAnalysisJson();
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, ResumeJdGapAnalysis.class);
    } catch (JacksonException e) {
      log.error("解析提案 JD Gap 失败: proposalId={}", proposal.getId(), e);
      throw new BusinessException(
          ErrorCode.RESUME_OPTIMIZATION_INVALID, "提案 JD Gap 内容损坏");
    }
  }

  /** 简历的最新待决策提案（前端回显） */
  @Transactional(readOnly = true)
  public ResumeOptimizationProposalEntity getLatestPending(Long resumeId) {
    return proposalRepository
        .findFirstByResumeIdAndStatusOrderByCreatedAtDesc(
            resumeId, ResumeOptimizationProposalEntity.ProposalStatus.PENDING)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.RESUME_OPTIMIZATION_PROPOSAL_NOT_FOUND,
            "简历没有待决策的优化提案: resumeId=" + resumeId));
  }

  /**
   * 状态流转（仅 PENDING → APPLIED / REJECTED 两个出口；非 PENDING 拒绝）。
   * 由 apply/reject 链路调用，保证幂等决策（重复应用直接报错）。
   *
   * @return 流转后的提案（供调用方直接回显决策结果）
   */
  @Transactional(rollbackFor = Exception.class)
  public ResumeOptimizationProposalEntity transitionFromPending(
      Long proposalId, ResumeOptimizationProposalEntity.ProposalStatus target) {
    ResumeOptimizationProposalEntity proposal = getProposal(proposalId);
    if (proposal.getStatus() != ResumeOptimizationProposalEntity.ProposalStatus.PENDING) {
      throw new BusinessException(
          ErrorCode.RESUME_OPTIMIZATION_INVALID,
          "提案已处理（当前状态: " + proposal.getStatus() + "），不能重复决策");
    }
    proposal.setStatus(target);
    proposal.setDecidedAt(java.time.LocalDateTime.now());
    return proposalRepository.save(proposal);
  }

  private String serialize(List<ResumePatchItem> patches) {
    try {
      return objectMapper.writeValueAsString(patches);
    } catch (JacksonException e) {
      log.error("序列化提案 Patch 列表失败", e);
      throw new BusinessException(ErrorCode.RESUME_OPTIMIZATION_INVALID, "保存提案失败");
    }
  }

  private String serializeNullable(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException e) {
      log.error("序列化 JD Gap 分析失败", e);
      throw new BusinessException(ErrorCode.RESUME_OPTIMIZATION_INVALID, "保存 JD Gap 失败");
    }
  }
}
