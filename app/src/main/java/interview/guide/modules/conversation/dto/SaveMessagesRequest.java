package interview.guide.modules.conversation.dto;

import java.util.List;

/** 批量保存消息请求：一次保存一轮（USER + ASSISTANT） */
public record SaveMessagesRequest(List<MessagePayload> messages) {

  public record MessagePayload(String role, String content, String blocks) {}
}