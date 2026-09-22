package interview.guide.modules.conversation.dto;

import java.util.List;

/**
 * 批量保存消息请求：一次保存一轮（USER + ASSISTANT）。
 *
 * <p>{@code status} 为本轮生成终态 COMPLETED / STOPPED / FAILED，缺省（null）按 COMPLETED 处理；
 * ASSISTANT 消息在未完成状态（STOPPED / FAILED）下允许内容为空——用户停止或生成失败时
 * 可能尚未产出任何内容，但该轮未完成这件事需要留痕。
 */
public record SaveMessagesRequest(List<MessagePayload> messages) {

  public record MessagePayload(String role, String content, String blocks, String status) {}
}
