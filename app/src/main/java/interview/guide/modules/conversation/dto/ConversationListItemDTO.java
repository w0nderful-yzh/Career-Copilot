package interview.guide.modules.conversation.dto;

import java.time.LocalDateTime;

/** 会话列表项 */
public record ConversationListItemDTO(
    Long id,
    String title,
    Integer messageCount,
    Boolean isPinned,
    LocalDateTime updatedAt) {}