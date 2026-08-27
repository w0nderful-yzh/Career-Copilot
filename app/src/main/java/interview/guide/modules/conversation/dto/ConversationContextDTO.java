package interview.guide.modules.conversation.dto;

import java.util.List;

/**
 * 会话上下文（Python Agent 短期记忆用）。
 *
 * <p>只返回最近 N 条消息的 role/content（不含 blocks 与完整历史），
 * 并附带会话级滚动摘要与消息总数，供 Python 判断是否触发滚动摘要。
 */
public record ConversationContextDTO(
    List<AgentMessageDTO> messages,
    String summary,
    long totalCount) {}