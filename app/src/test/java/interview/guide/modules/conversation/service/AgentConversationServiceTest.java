package interview.guide.modules.conversation.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.conversation.dto.ConversationListItemDTO;
import interview.guide.modules.conversation.dto.CreateConversationRequest;
import interview.guide.modules.conversation.dto.SaveMessagesRequest;
import interview.guide.modules.conversation.dto.SaveMessagesRequest.MessagePayload;
import interview.guide.modules.conversation.model.AgentConversationEntity;
import interview.guide.modules.conversation.repository.AgentConversationRepository;
import interview.guide.modules.conversation.repository.AgentMessageRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentConversationServiceTest {

  @Mock
  private AgentConversationRepository conversationRepository;
  @Mock
  private AgentMessageRepository messageRepository;

  @InjectMocks
  private AgentConversationService conversationService;

  @Nested
  @DisplayName("会话创建")
  class CreateConversation {

    @Test
    @DisplayName("无标题时默认「新对话」")
    void createWithoutTitleUsesDefault() {
      when(conversationRepository.save(any())).thenAnswer(invocation -> {
        AgentConversationEntity entity = invocation.getArgument(0);
        entity.setId(1L);
        return entity;
      });

      ConversationListItemDTO item = conversationService.createConversation(null);

      assertThat(item.title()).isEqualTo("新对话");
      assertThat(item.messageCount()).isZero();
      verify(conversationRepository).save(any());
    }

    @Test
    @DisplayName("超长标题被截断")
    void createTruncatesLongTitle() {
      when(conversationRepository.save(any())).thenAnswer(invocation -> {
        AgentConversationEntity entity = invocation.getArgument(0);
        entity.setId(2L);
        return entity;
      });

      String longTitle = "x".repeat(100);
      ConversationListItemDTO item =
          conversationService.createConversation(new CreateConversationRequest(longTitle));

      assertThat(item.title().length()).isLessThanOrEqualTo(41);
    }
  }

  @Nested
  @DisplayName("消息保存")
  class SaveMessages {

    @Test
    @DisplayName("保存一轮消息并自动生成标题（首条用户消息截断）")
    void saveMessagesGeneratesTitleFromFirstUserMessage() {
      AgentConversationEntity conversation = new AgentConversationEntity();
      conversation.setId(10L);
      conversation.setTitle("新对话");
      when(conversationRepository.findByIdAndUserId(10L, "default"))
          .thenReturn(Optional.of(conversation));
      when(conversationRepository.save(any())).thenReturn(conversation);

      conversationService.saveMessages(10L, new SaveMessagesRequest(List.of(
          new MessagePayload("USER", "我准备找 Java 后端实习，帮我看看应该怎么准备。", null),
          new MessagePayload("ASSISTANT", "好的，让我看看你的简历。", null))));

      assertThat(conversation.getTitle()).isEqualTo("我准备找 Java 后端实习，帮我看看应该怎么准备。");
      assertThat(conversation.getMessageCount()).isEqualTo(2);
      verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("空消息列表抛参数错误")
    void saveMessagesWithEmptyListFails() {
      AgentConversationEntity conversation = new AgentConversationEntity();
      conversation.setId(10L);
      when(conversationRepository.findByIdAndUserId(10L, "default"))
          .thenReturn(Optional.of(conversation));

      assertThatThrownBy(() -> conversationService.saveMessages(
          10L, new SaveMessagesRequest(List.of())))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.CONVERSATION_MESSAGE_INVALID.getCode());
    }

    @Test
    @DisplayName("非法消息角色抛参数错误")
    void saveMessagesWithInvalidRoleFails() {
      AgentConversationEntity conversation = new AgentConversationEntity();
      conversation.setId(10L);
      when(conversationRepository.findByIdAndUserId(10L, "default"))
          .thenReturn(Optional.of(conversation));

      assertThatThrownBy(() -> conversationService.saveMessages(
          10L, new SaveMessagesRequest(List.of(
              new MessagePayload("SYSTEM", "hello", null)))))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.CONVERSATION_MESSAGE_INVALID.getCode());
    }

    @Test
    @DisplayName("消息内容为空抛参数错误")
    void saveMessagesWithBlankContentFails() {
      AgentConversationEntity conversation = new AgentConversationEntity();
      conversation.setId(10L);
      when(conversationRepository.findByIdAndUserId(10L, "default"))
          .thenReturn(Optional.of(conversation));

      assertThatThrownBy(() -> conversationService.saveMessages(
          10L, new SaveMessagesRequest(List.of(
              new MessagePayload("USER", "  ", null)))))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.CONVERSATION_MESSAGE_INVALID.getCode());
    }
  }

  @Nested
  @DisplayName("会话不存在")
  class ConversationNotFound {

    @Test
    @DisplayName("保存消息到不存在的会话抛 CONVERSATION_NOT_FOUND")
    void saveMessagesToMissingConversationFails() {
      when(conversationRepository.findByIdAndUserId(anyLong(), anyString()))
          .thenReturn(Optional.empty());

      assertThatThrownBy(() -> conversationService.saveMessages(
          999L, new SaveMessagesRequest(List.of(
              new MessagePayload("USER", "hello", null)))))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.CONVERSATION_NOT_FOUND.getCode());
    }
  }
}