package interview.guide.modules.conversation.dto;

/** 创建会话请求：title 可选，为空时默认 "新对话" */
public record CreateConversationRequest(String title) {}