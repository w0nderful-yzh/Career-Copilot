package interview.guide.modules.conversation.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 会话详情（含消息历史） */
public record ConversationDetailDTO(
    Long id,
    String title,
    Boolean isPinned,
    List<AgentMessageDTO> messages,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}