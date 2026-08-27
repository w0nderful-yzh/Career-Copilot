package interview.guide.modules.conversation.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.conversation.dto.AgentMessageDTO;
import interview.guide.modules.conversation.dto.ConversationDetailDTO;
import interview.guide.modules.conversation.dto.ConversationListItemDTO;
import interview.guide.modules.conversation.dto.CreateConversationRequest;
import interview.guide.modules.conversation.dto.SaveMessagesRequest;
import interview.guide.modules.conversation.dto.SaveMessagesRequest.MessagePayload;
import interview.guide.modules.conversation.model.AgentConversationEntity;
import interview.guide.modules.conversation.model.AgentMessageEntity;
import interview.guide.modules.conversation.model.AgentMessageEntity.MessageRole;
import interview.guide.modules.conversation.repository.AgentConversationRepository;
import interview.guide.modules.conversation.repository.AgentMessageRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copilot 对话服务。
 *
 * <p>Agent 对话会话与消息的持久化（System of Record）。Python Agent 通过
 * 本模块的 HTTP API 读写会话数据，本服务不包含任何 Agent 推理逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentConversationService {

  /** 当前系统为单用户，预留 user_id 供未来多用户隔离 */
  public static final String DEFAULT_USER_ID = "default";

  private static final String DEFAULT_TITLE = "新对话";
  private static final int TITLE_MAX_LENGTH = 40;

  private final AgentConversationRepository conversationRepository;
  private final AgentMessageRepository messageRepository;

  @Transactional
  public ConversationListItemDTO createConversation(CreateConversationRequest request) {
    AgentConversationEntity conversation = new AgentConversationEntity();
    conversation.setUserId(DEFAULT_USER_ID);
    conversation.setTitle(trimTitle(request == null ? null : request.title()));
    conversationRepository.save(conversation);
    log.info("Conversation created: id={}", conversation.getId());
    return toListItem(conversation);
  }

  public List<ConversationListItemDTO> listConversations() {
    return conversationRepository
        .findActiveByUserIdOrderByPinnedAndUpdatedAtDesc(DEFAULT_USER_ID)
        .stream()
        .map(this::toListItem)
        .toList();
  }

  public ConversationDetailDTO getConversationDetail(Long conversationId) {
    AgentConversationEntity conversation = getConversationOrThrow(conversationId);
    // 消息单独查询，避免一次加载会话实体再触达懒加载集合
    List<AgentMessageEntity> messages =
        messageRepository.findByConversationIdOrderByMessageOrderAsc(conversationId);
    List<AgentMessageDTO> messageDTOs = messages.stream()
        .map(message -> new AgentMessageDTO(
            message.getId(),
            message.getRole().name(),
            message.getContent(),
            message.getBlocks(),
            message.getCreatedAt()))
        .toList();
    return new ConversationDetailDTO(
        conversation.getId(),
        conversation.getTitle(),
        conversation.getIsPinned(),
        messageDTOs,
        conversation.getCreatedAt(),
        conversation.getUpdatedAt());
  }

  @Transactional
  public void renameConversation(Long conversationId, String title) {
    AgentConversationEntity conversation = getConversationOrThrow(conversationId);
    String newTitle = trimTitle(title);
    conversation.setTitle(newTitle);
    conversationRepository.save(conversation);
  }

  @Transactional
  public void togglePin(Long conversationId) {
    AgentConversationEntity conversation = getConversationOrThrow(conversationId);
    conversation.setIsPinned(!Boolean.TRUE.equals(conversation.getIsPinned()));
    conversationRepository.save(conversation);
  }

  @Transactional
  public void deleteConversation(Long conversationId) {
    AgentConversationEntity conversation = getConversationOrThrow(conversationId);
    conversationRepository.delete(conversation);
    log.info("Conversation deleted: id={}", conversationId);
  }

  /**
   * 批量保存一轮消息（USER + ASSISTANT，含结构化 blocks）。
   *
   * <p>若会话标题仍为默认值且本批首条为 USER 消息，则用其内容截断生成标题。
   * messageOrder 基于会话现有消息数续排。
   */
  @Transactional
  public void saveMessages(Long conversationId, SaveMessagesRequest request) {
    AgentConversationEntity conversation = getConversationOrThrow(conversationId);
    List<MessagePayload> payloads = request.messages();
    if (payloads == null || payloads.isEmpty()) {
      throw new BusinessException(ErrorCode.CONVERSATION_MESSAGE_INVALID, "消息列表不能为空");
    }

    int order = conversation.getMessages().size();
    for (MessagePayload payload : payloads) {
      MessageRole role = parseRole(payload.role());
      if (payload.content() == null || payload.content().isBlank()) {
        throw new BusinessException(ErrorCode.CONVERSATION_MESSAGE_INVALID,
            "消息内容不能为空: role=" + payload.role());
      }
      AgentMessageEntity message = new AgentMessageEntity();
      message.setRole(role);
      message.setContent(payload.content());
      message.setBlocks(payload.blocks());
      message.setMessageOrder(order++);
      conversation.addMessage(message);
    }

    // 默认标题用首条用户消息截断生成（纯规则，不调用 LLM）
    if (DEFAULT_TITLE.equals(conversation.getTitle())) {
      MessagePayload first = payloads.get(0);
      if (MessageRole.USER == parseRole(first.role())) {
        conversation.setTitle(truncateTitle(first.content()));
      }
    }
    conversationRepository.save(conversation);
    log.info("Conversation messages saved: id={}, count={}", conversationId, payloads.size());
  }

  private AgentConversationEntity getConversationOrThrow(Long conversationId) {
    return conversationRepository.findByIdAndUserId(conversationId, DEFAULT_USER_ID)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.CONVERSATION_NOT_FOUND, "对话不存在: id=" + conversationId));
  }

  private MessageRole parseRole(String role) {
    try {
      return MessageRole.valueOf(role.toUpperCase());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new BusinessException(
          ErrorCode.CONVERSATION_MESSAGE_INVALID, "非法消息角色: " + role);
    }
  }

  private ConversationListItemDTO toListItem(AgentConversationEntity conversation) {
    return new ConversationListItemDTO(
        conversation.getId(),
        conversation.getTitle(),
        conversation.getMessageCount(),
        conversation.getIsPinned(),
        conversation.getUpdatedAt());
  }

  private String trimTitle(String title) {
    if (title == null || title.isBlank()) {
      return DEFAULT_TITLE;
    }
    return truncateTitle(title.trim());
  }

  private String truncateTitle(String content) {
    String singleLine = content.replaceAll("\\s+", " ").trim();
    if (singleLine.length() <= TITLE_MAX_LENGTH) {
      return singleLine;
    }
    return singleLine.substring(0, TITLE_MAX_LENGTH) + "…";
  }
}