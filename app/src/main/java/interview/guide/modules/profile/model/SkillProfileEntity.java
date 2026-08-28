package interview.guide.modules.profile.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 技能画像实体：某项技能的聚合评分（Evidence-driven，非 LLM 主观生成）。
 *
 * <p>score 由 {@code SkillProfileAggregator} 基于全部 Evidence 计算得出，
 * 不允许其他链路直接改写；evidenceCount 为当前计分证据条数，用于追溯可信度。
 */
@Entity
@Table(name = "skill_profiles",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_skill_profile", columnNames = {"userId", "skill"})
    })
@Getter
@Setter
@NoArgsConstructor
public class SkillProfileEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 用户归属，当前单用户默认 "default"，预留多用户隔离 */
  @Column(name = "user_id", nullable = false, length = 64)
  private String userId = "default";

  /** 技能名（展示用，如 "MySQL"、"JVM"；对齐面试 category 标签） */
  @Column(nullable = false, length = 128)
  private String skill;

  /** 聚合评分 (0-100)，由 Aggregator 计算 */
  @Column(nullable = false)
  private Integer score = 0;

  /** 参与计分的证据条数 */
  @Column(nullable = false)
  private Integer evidenceCount = 0;

  /** 最后一次聚合时间 */
  @Column(nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    updatedAt = LocalDateTime.now();
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
