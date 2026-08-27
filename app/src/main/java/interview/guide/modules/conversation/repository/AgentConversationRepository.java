package interview.guide.modules.conversation.repository;

import interview.guide.modules.conversation.model.AgentConversationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentConversationRepository extends JpaRepository<AgentConversationEntity, Long> {

  /** 会话列表：置顶优先，其次按更新时间倒序 */
  @Query("""
      SELECT c FROM AgentConversationEntity c
      WHERE c.userId = :userId AND c.status = 'ACTIVE'
      ORDER BY c.isPinned DESC, c.updatedAt DESC
      """)
  java.util.List<AgentConversationEntity> findActiveByUserIdOrderByPinnedAndUpdatedAtDesc(
      @Param("userId") String userId);

  /** 按用户加载会话（所有权校验） */
  Optional<AgentConversationEntity> findByIdAndUserId(Long id, String userId);
}