package interview.guide.modules.conversation.repository;

import interview.guide.modules.conversation.model.AgentConversationEntity;
import interview.guide.modules.conversation.model.AgentConversationEntity.ConversationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentConversationRepository extends JpaRepository<AgentConversationEntity, Long> {

  /**
   * 会话列表：按状态过滤（ACTIVE 活跃 / ARCHIVED 已归档），置顶优先，其次按更新时间倒序。
   *
   * <p>状态作为参数而非写死 ACTIVE：归档集合此前只进不出——没有任何归档入口，
   * 也没有查看/恢复入口，已归档会话实际上是个黑洞。
   */
  @Query("""
      SELECT c FROM AgentConversationEntity c
      WHERE c.userId = :userId AND c.status = :status
      ORDER BY c.isPinned DESC, c.updatedAt DESC
      """)
  List<AgentConversationEntity> findByUserIdAndStatusOrderByPinnedAndUpdatedAtDesc(
      @Param("userId") String userId, @Param("status") ConversationStatus status);

  /** 按用户加载会话（所有权校验） */
  Optional<AgentConversationEntity> findByIdAndUserId(Long id, String userId);
}