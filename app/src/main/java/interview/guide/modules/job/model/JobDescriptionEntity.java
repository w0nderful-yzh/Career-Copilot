package interview.guide.modules.job.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * JD（岗位描述）附件实体（P2-5）。
 *
 * <p>与简历分离存储：内容解析后的纯文本直接落库（Tika 提取），
 * 不进简历库、不参与简历 hash 去重语义。一期无更新，删除即级联清理解绑。
 */
@Getter
@Setter
@Entity
@Table(name = "job_descriptions")
public class JobDescriptionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 展示标题：优先取文件名（去扩展名），可后续由解析出的岗位名覆盖 */
  @Column(nullable = false)
  private String title;

  /** 公司名（解析可得，一期从文件名/内容尽力提取，可空） */
  private String company;

  /** JD 全文（Tika 提取的纯文本，Agent 取数基础） */
  @Column(nullable = false, columnDefinition = "TEXT")
  private String contentText;

  /** 原始文件存储键（可空：允许直接粘贴文本创建） */
  @Column(name = "file_key")
  private String fileKey;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();
}
