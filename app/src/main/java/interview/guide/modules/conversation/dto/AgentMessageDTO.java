package interview.guide.modules.conversation.dto;

import java.time.LocalDateTime;

/** 单条消息：content 文本 + blocks JSON（结构化 Block 数组） */
public record AgentMessageDTO(
    Long id,
    String role,
    String content,
    String blocks,
    LocalDateTime createdAt) {}