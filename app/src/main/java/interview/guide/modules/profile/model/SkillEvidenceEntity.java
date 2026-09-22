package interview.guide.modules.profile.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 技能证据实体：一条可追溯的评分来源记录。
 *
 * <p>(userId, skill, sourceType, sourceId) 唯一——评估任务重放 / 消息重复消费时
 * 不会重复计分，这是 Aggregator 幂等性的存储基础。
 */
@Entity
@Table(name = "skill_evidence",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_skill_evidence",
            columnNames = {"userId", "skill", "sourceType", "sourceId"})
    })
@Getter
@Setter
@NoArgsConstructor
public class SkillEvidenceEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 用户归属，当前单用户默认 "default" */
  @Column(name = "user_id", nullable = false, length = 64)
  private String userId = "default";

  /** 技能名（与 skill_profiles.skill 对齐） */
  @Column(nullable = false, length = 128)
  private String skill;

  /** 证据来源类型 */
  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 32)
  private EvidenceSourceType sourceType;

  /**
   * 来源标识：INTERVIEW_SESSION 用 sessionId，INTERVIEW_TURN 用
   * "{sessionId}:{questionIndex}"，RESUME 用 resumeId。
   */
  @Column(name = "source_id", nullable = false, length = 64)
  private String sourceId;

  /**
   * 该证据的评分 (0-100)；{@code null} 表示**声明型证据**。
   *
   * <p>声明型证据目前只有简历来源：结构化简历里列了这项技能，但没有任何评分依据。
   * 它不参与画像聚合（聚合只看有分证据）——给未验证的技能编一个分数会违反
   * Core-4「画像分必须能由证据逐条还原」。它的用途是让画像能表达
   * 「简历已列 · 待验证」，并作为下一场面试选 focus 的依据（没考过的技能最该被考）。
   */
  @Column
  private Integer score;

  /** 证据发生时间（面试取 completedAt / answeredAt） */
  @Column(name = "occurred_at", nullable = false)
  private LocalDateTime occurredAt;

  public SkillEvidenceEntity(String userId, String skill, EvidenceSourceType sourceType,
                             String sourceId, Integer score, LocalDateTime occurredAt) {
    this.userId = userId;
    this.skill = skill;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.score = score;
    this.occurredAt = occurredAt;
  }
}
