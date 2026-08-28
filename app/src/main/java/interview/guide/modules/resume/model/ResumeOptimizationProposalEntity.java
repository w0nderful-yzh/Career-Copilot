package interview.guide.modules.resume.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 简历优化提案实体（P2-1）：Agent 生成的 Patch 集合持久化。
 *
 * <p>HITL 审计追溯的关键：LLM 提了什么建议、用户接受了什么，全部留痕。
 * patches 整体 JSON 存储（apply 时整体读取逐条校验），状态由用户决策驱动。
 */
@Entity
@Table(name = "resume_optimization_proposals", indexes = {
    @Index(name = "idx_resume_optimization_proposal_resume",
        columnList = "resumeId,createdAt")
})
@Getter
@Setter
@NoArgsConstructor
public class ResumeOptimizationProposalEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 目标简历 */
  @Column(name = "resume_id", nullable = false)
  private Long resumeId;

  /** 提案基于的版本（apply 时在该版本上生成新版本） */
  @Column(name = "source_version_id", nullable = false)
  private Long sourceVersionId;

  /** 优化模式：GENERAL / TARGET_DIRECTION / JD_TARGETED */
  @Enumerated(EnumType.STRING)
  @Column(name = "optimization_type", nullable = false, length = 32)
  private OptimizationType optimizationType = OptimizationType.GENERAL;

  /** 提案状态：PENDING → APPLIED / REJECTED（用户决策驱动） */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ProposalStatus status = ProposalStatus.PENDING;

  /** Agent 对本轮优化的一句话总结（展示用） */
  @Column(columnDefinition = "TEXT")
  private String summary;

  /** Patch 列表 JSON（ResumePatchItem 数组） */
  @Column(name = "patches_json", nullable = false, columnDefinition = "TEXT")
  private String patchesJson;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  /** 用户决策时间（应用/拒绝） */
  @Column(name = "decided_at")
  private LocalDateTime decidedAt;

  public enum OptimizationType {
    GENERAL,           // 通用优化
    TARGET_DIRECTION,  // 目标方向优化
    JD_TARGETED        // 针对 JD 优化（P2-5 点亮）
  }

  public enum ProposalStatus {
    PENDING,   // 待用户决策
    APPLIED,   // 已应用（生成新版本）
    REJECTED   // 用户拒绝
  }

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
  }
}
