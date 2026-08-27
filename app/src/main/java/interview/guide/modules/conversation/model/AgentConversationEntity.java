package interview.guide.modules.conversation.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Copilot 对话会话实体。
 *
 * <p>Agent 对话的容器，包含多条消息。结构与 rag_chat_sessions 一致，
 * 但去除知识库绑定，并预留 user_id 支持未来多用户隔离。
 */
@Entity
@Table(name = "agent_conversations", indexes = {
    @Index(name = "idx_agent_conversation_updated", columnList = "updatedAt")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentConversationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String title;

  /** 用户归属，当前单用户默认 "default"，预留多用户隔离 */
  @Column(name = "user_id", nullable = false, length = 64)
  private String userId = "default";

  @Enumerated(EnumType.STRING)
  @Column(length = 20)
  private ConversationStatus status = ConversationStatus.ACTIVE;

  @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("messageOrder ASC")
  private List<AgentMessageEntity> messages = new ArrayList<>();

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /** 更新时间（最后一次消息时间） */
  private LocalDateTime updatedAt;

  /** 消息数量（冗余字段，方便查询） */
  @Column(nullable = false)
  private Integer messageCount = 0;

  @Column(name = "is_pinned", nullable = false)
  private Boolean isPinned = false;

  public enum ConversationStatus {
    ACTIVE,    // 活跃会话
    ARCHIVED   // 已归档
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

  @PostLoad
  protected void onLoad() {
    // 兼容旧数据：确保状态与置顶字段有值
    if (status == null) {
      status = ConversationStatus.ACTIVE;
    }
    if (isPinned == null) {
      isPinned = false;
    }
  }

  /** 便捷方法：追加消息并同步维护计数与更新时间 */
  public void addMessage(AgentMessageEntity message) {
    messages.add(message);
    message.setConversation(this);
    messageCount = messages.size();
    updatedAt = LocalDateTime.now();
  }
}