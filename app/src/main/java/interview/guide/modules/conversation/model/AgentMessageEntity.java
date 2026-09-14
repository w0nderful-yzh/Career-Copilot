package interview.guide.modules.conversation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Copilot 对话消息实体。
 *
 * <p>content 为纯文本；blocks 为结构化 Block 的 JSON 数组（与 Python 协议对齐），
 * 回放时由前端受控渲染器解析渲染。
 */
@Entity
@Table(name = "agent_messages", indexes = {
    @Index(name = "idx_agent_message_order", columnList = "conversationId, messageOrder")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentMessageEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "conversation_id", nullable = false)
  private AgentConversationEntity conversation;

  @Enumerated(EnumType.STRING)
  @Column(length = 20, nullable = false)
  private MessageRole role;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  /** 结构化 Block 的 JSON 数组（如 [{"type":"action",...}]），可空 */
  @Column(columnDefinition = "TEXT")
  private String blocks;

  /** 同一会话内的排序键 */
  @Column(name = "message_order", nullable = false)
  private Integer messageOrder;

  /**
   * 本轮生成终态（P1 待收口）。
   *
   * <p>用户主动停止（STOPPED）与生成失败（FAILED）时 content 可能为空（尚未产出内容），
   * 因此非 COMPLETED 状态下助手消息允许空内容；其余情况仍要求内容非空。
   */
  @Enumerated(EnumType.STRING)
  @Column(length = 20, nullable = false)
  private MessageStatus status = MessageStatus.COMPLETED;

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;

  public enum MessageRole {
    USER,
    ASSISTANT
  }

  /** 消息终态：正常完成 / 用户停止 / 生成失败 */
  public enum MessageStatus {
    COMPLETED,
    STOPPED,
    FAILED
  }

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = LocalDateTime.now();
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}