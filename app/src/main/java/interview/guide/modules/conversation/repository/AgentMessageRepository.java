package interview.guide.modules.conversation.repository;

import interview.guide.modules.conversation.model.AgentMessageEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentMessageRepository extends JpaRepository<AgentMessageEntity, Long> {

  /** 会话内按排序键正序取消息（详情回放用） */
  java.util.List<AgentMessageEntity> findByConversationIdOrderByMessageOrderAsc(Long conversationId);

  /** 会话内最近 N 条消息（按排序键倒序取，供 Python 短期记忆上下文） */
  List<AgentMessageEntity> findByConversationIdOrderByMessageOrderDesc(
      Long conversationId, Pageable pageable);

  /** 会话内消息总数（供 Python 判断是否触发滚动摘要） */
  long countByConversationId(Long conversationId);
}