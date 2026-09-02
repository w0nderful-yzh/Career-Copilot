package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeOptimizationProposalEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 简历优化提案 Repository
 */
@Repository
public interface ResumeOptimizationProposalRepository
    extends JpaRepository<ResumeOptimizationProposalEntity, Long> {

  /** 简历的提案历史（新在前） */
  List<ResumeOptimizationProposalEntity> findByResumeIdOrderByCreatedAtDesc(Long resumeId);

  /** 最新待决策提案（前端回显用） */
  Optional<ResumeOptimizationProposalEntity> findFirstByResumeIdAndStatusOrderByCreatedAtDesc(
      Long resumeId, ResumeOptimizationProposalEntity.ProposalStatus status);
}
