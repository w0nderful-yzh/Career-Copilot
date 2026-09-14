package interview.guide.modules.conversation.dto;

import java.time.LocalDateTime;

/**
 * 单条消息：content 文本 + blocks JSON（结构化 Block 数组） + 终态 status。
 *
 * <p>status 取 COMPLETED / STOPPED / FAILED；短期记忆取数场景传 null（不需要终态）。
 */
public record AgentMessageDTO(
    Long id,
    String role,
    String content,
    String blocks,
    String status,
    LocalDateTime createdAt) {}