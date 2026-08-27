package interview.guide.modules.conversation.repository;

import interview.guide.modules.conversation.model.AgentMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentMessageRepository extends JpaRepository<AgentMessageEntity, Long> {

  /** 会话内按排序键正序取消息（详情回放用） */
  java.util.List<AgentMessageEntity> findByConversationIdOrderByMessageOrderAsc(Long conversationId);
}