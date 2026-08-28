package interview.guide.modules.resume.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 简历版本实体：简历的结构化快照（P2-0 简历优化地基）。
 *
 * <p>原始导入是 V1（source=IMPORT），AI 优化产物为新版本（source=AI_OPTIMIZE），
 * 任何修改都不覆盖旧版本。contentJson 为结构化 Resume JSON，
 * Patch 按其 JSON path 定位（P2-1）。
 */
@Entity
@Table(name = "resume_versions",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_resume_version", columnNames = {"resumeId", "version"})
    })
@Getter
@Setter
@NoArgsConstructor
public class ResumeVersionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 归属简历 */
  @Column(name = "resume_id", nullable = false)
  private Long resumeId;

  /** 版本号（同一简历内递增，V1 = 原始导入） */
  @Column(nullable = false)
  private Integer version;

  /** 来源版本（AI 优化基于哪个版本产出；原始导入为 null） */
  @Column(name = "source_version_id")
  private Long sourceVersionId;

  /** 优化类型（GENERAL / TARGET_DIRECTION / JD_TARGETED；导入版本为 null） */
  @Column(name = "optimization_type", length = 32)
  private String optimizationType;

  /** 目标 JD id（JD_TARGETED 时记录；JD 附件功能 P2-5 落地后启用） */
  @Column(name = "target_job_id")
  private Long targetJobId;

  /** 结构化 Resume JSON（basicInfo/education/experience/projects/skills/customSections） */
  @Column(name = "content_json", nullable = false, columnDefinition = "TEXT")
  private String contentJson;

  /** 版本来源：IMPORT（原始导入）/ USER_EDIT / AI_OPTIMIZE */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private VersionSource source;

  /** 确认状态：解析结果需用户确认后才作为优化基础 */
  @Enumerated(EnumType.STRING)
  @Column(name = "confirmation_status", nullable = false, length = 32)
  private ConfirmationStatus confirmationStatus = ConfirmationStatus.PENDING_CONFIRMATION;

  /** 解析缺失/低置信字段清单（JSON 数组，引导用户补录；无缺失为 null） */
  @Column(name = "missing_fields_json", columnDefinition = "TEXT")
  private String missingFieldsJson;

  /** 来源数据时间（IMPORT = 原文件上传时间；AI 优化 = 来源版本的创建时间） */
  @Column(name = "source_created_at", nullable = false)
  private LocalDateTime sourceCreatedAt;

  /** 版本创建时间 */
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  public enum VersionSource {
    IMPORT,       // 原始导入（Tika + LLM 解析）
    USER_EDIT,    // 用户手动编辑
    AI_OPTIMIZE   // AI 优化产物
  }

  public enum ConfirmationStatus {
    PENDING_CONFIRMATION,  // 解析完成，待用户确认
    ACTIVE,                // 用户已确认，可作为优化基础
    NEED_USER_INFO         // 关键字段缺失，需用户补录
  }

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
  }
}
