package interview.guide.modules.conversation.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.conversation.dto.ConversationListItemDTO;
import interview.guide.modules.conversation.dto.CreateConversationRequest;
import interview.guide.modules.conversation.dto.SaveMessagesRequest;
import interview.guide.modules.conversation.dto.SaveMessagesRequest.MessagePayload;
import interview.guide.modules.conversation.model.AgentConversationEntity;
import interview.guide.modules.conversation.model.AgentMessageEntity;
import interview.guide.modules.conversation.model.AgentMessageEntity.MessageStatus;
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

  /** 构造一个已存在的会话并让仓储返回它 */
  private AgentConversationEntity stubConversation(long id) {
    AgentConversationEntity conversation = new AgentConversationEntity();
    conversation.setId(id);
    when(conversationRepository.findByIdAndUserId(id, "default"))
        .thenReturn(Optional.of(conversation));
    return conversation;
  }

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
      AgentConversationEntity conversation = stubConversation(10L);
      conversation.setTitle("新对话");
      when(conversationRepository.save(any())).thenReturn(conversation);

      conversationService.saveMessages(10L, new SaveMessagesRequest(List.of(
          new MessagePayload("USER", "我准备找 Java 后端实习，帮我看看应该怎么准备。", null, null),
          new MessagePayload("ASSISTANT", "好的，让我看看你的简历。", null, null))));

      assertThat(conversation.getTitle()).isEqualTo("我准备找 Java 后端实习，帮我看看应该怎么准备。");
      assertThat(conversation.getMessageCount()).isEqualTo(2);
      verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("空消息列表抛参数错误")
    void saveMessagesWithEmptyListFails() {
      stubConversation(10L);

      assertThatThrownBy(() -> conversationService.saveMessages(
          10L, new SaveMessagesRequest(List.of())))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.CONVERSATION_MESSAGE_INVALID.getCode());
    }

    @Test
    @DisplayName("非法消息角色抛参数错误")
    void saveMessagesWithInvalidRoleFails() {
      stubConversation(10L);

      assertThatThrownBy(() -> conversationService.saveMessages(
          10L, new SaveMessagesRequest(List.of(
              new MessagePayload("SYSTEM", "hello", null, null)))))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.CONVERSATION_MESSAGE_INVALID.getCode());
    }

    @Test
    @DisplayName("消息内容为空抛参数错误")
    void saveMessagesWithBlankContentFails() {
      stubConversation(10L);

      assertThatThrownBy(() -> conversationService.saveMessages(
          10L, new SaveMessagesRequest(List.of(
              new MessagePayload("USER", "  ", null, null)))))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.CONVERSATION_MESSAGE_INVALID.getCode());
    }
  }

  @Nested
  @DisplayName("消息终态（P1 停止生成）")
  class MessageStatusPersistence {

    @Test
    @DisplayName("未带 status 时缺省落 COMPLETED")
    void defaultsToCompleted() {
      AgentConversationEntity conversation = stubConversation(30L);
      when(conversationRepository.save(any())).thenReturn(conversation);

      conversationService.saveMessages(30L, new SaveMessagesRequest(List.of(
          new MessagePayload("USER", "你好", null, null),
          new MessagePayload("ASSISTANT", "你好，我能帮你什么？", null, null))));

      assertThat(conversation.getMessages())
          .extracting(AgentMessageEntity::getStatus)
          .containsExactly(MessageStatus.COMPLETED, MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("停止生成：助手消息落 STOPPED 且保留已生成的部分内容")
    void persistsStoppedWithPartialContent() {
      AgentConversationEntity conversation = stubConversation(31L);
      when(conversationRepository.save(any())).thenReturn(conversation);

      conversationService.saveMessages(31L, new SaveMessagesRequest(List.of(
          new MessagePayload("USER", "帮我复盘这次面试", null, "COMPLETED"),
          new MessagePayload("ASSISTANT", "先看整体分数：", null, "STOPPED"))));

      assertThat(conversation.getMessages())
          .extracting(AgentMessageEntity::getStatus)
          .containsExactly(MessageStatus.COMPLETED, MessageStatus.STOPPED);
      assertThat(conversation.getMessages().get(1).getContent()).isEqualTo("先看整体分数：");
    }

    @Test
    @DisplayName("未完成状态下助手消息允许空内容：停止且尚未产出内容")
    void allowsEmptyContentWhenStopped() {
      AgentConversationEntity conversation = stubConversation(32L);
      when(conversationRepository.save(any())).thenReturn(conversation);

      conversationService.saveMessages(32L, new SaveMessagesRequest(List.of(
          new MessagePayload("USER", "帮我复盘这次面试", null, null),
          new MessagePayload("ASSISTANT", "", null, "STOPPED"))));

      assertThat(conversation.getMessages()).hasSize(2);
      assertThat(conversation.getMessages().get(1).getStatus()).isEqualTo(MessageStatus.STOPPED);
      assertThat(conversation.getMessages().get(1).getContent()).isEmpty();
    }

    @Test
    @DisplayName("未完成状态下助手消息允许空内容：生成失败且尚未产出内容")
    void allowsEmptyContentWhenFailed() {
      AgentConversationEntity conversation = stubConversation(37L);
      when(conversationRepository.save(any())).thenReturn(conversation);

      conversationService.saveMessages(37L, new SaveMessagesRequest(List.of(
          new MessagePayload("USER", "帮我复盘这次面试", null, null),
          new MessagePayload("ASSISTANT", null, null, "FAILED"))));

      assertThat(conversation.getMessages()).hasSize(2);
      assertThat(conversation.getMessages().get(1).getStatus()).isEqualTo(MessageStatus.FAILED);
      assertThat(conversation.getMessages().get(1).getContent()).isEmpty();
    }

    @Test
    @DisplayName("空内容豁免只对未完成的助手消息生效：用户消息空内容仍被拒绝")
    void emptyContentStillRejectedForUserMessage() {
      stubConversation(33L);

      assertThatThrownBy(() -> conversationService.saveMessages(
          33L, new SaveMessagesRequest(List.of(
              new MessagePayload("USER", "", null, "STOPPED")))))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.CONVERSATION_MESSAGE_INVALID.getCode());
    }

    @Test
    @DisplayName("已完成的助手消息空内容仍被拒绝")
    void emptyContentRejectedForCompletedAssistant() {
      stubConversation(38L);

      assertThatThrownBy(() -> conversationService.saveMessages(
          38L, new SaveMessagesRequest(List.of(
              new MessagePayload("ASSISTANT", "  ", null, "COMPLETED")))))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.CONVERSATION_MESSAGE_INVALID.getCode());
    }

    @Test
    @DisplayName("生成失败：助手消息落 FAILED")
    void persistsFailed() {
      AgentConversationEntity conversation = stubConversation(34L);
      when(conversationRepository.save(any())).thenReturn(conversation);

      conversationService.saveMessages(34L, new SaveMessagesRequest(List.of(
          new MessagePayload("USER", "帮我复盘这次面试", null, null),
          new MessagePayload("ASSISTANT", "先看整体", null, "FAILED"))));

      assertThat(conversation.getMessages().get(1).getStatus()).isEqualTo(MessageStatus.FAILED);
    }

    @Test
    @DisplayName("非法 status 抛参数错误")
    void rejectsInvalidStatus() {
      stubConversation(35L);

      assertThatThrownBy(() -> conversationService.saveMessages(
          35L, new SaveMessagesRequest(List.of(
              new MessagePayload("USER", "你好", null, "CANCELLED")))))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.CONVERSATION_MESSAGE_INVALID.getCode());
    }

    @Test
    @DisplayName("status 小写也可解析（大小写不敏感）")
    void acceptsLowerCaseStatus() {
      AgentConversationEntity conversation = stubConversation(36L);
      when(conversationRepository.save(any())).thenReturn(conversation);

      conversationService.saveMessages(36L, new SaveMessagesRequest(List.of(
          new MessagePayload("USER", "你好", null, "completed"))));

      assertThat(conversation.getMessages().get(0).getStatus())
          .isEqualTo(MessageStatus.COMPLETED);
    }
  }

  @Nested
  @DisplayName("会话详情终态透出")
  class DetailExposesStatus {

    @Test
    @DisplayName("详情按消息返回 status，供前端还原「已停止」")
    void exposesStatusInDetail() {
      AgentConversationEntity conversation = stubConversation(40L);
      conversation.setTitle("复盘");
      AgentMessageEntity stopped = new AgentMessageEntity();
      stopped.setId(1L);
      stopped.setRole(AgentMessageEntity.MessageRole.ASSISTANT);
      stopped.setContent("先看整体分数：");
      stopped.setStatus(MessageStatus.STOPPED);
      when(messageRepository.findByConversationIdOrderByMessageOrderAsc(40L))
          .thenReturn(List.of(stopped));

      var detail = conversationService.getConversationDetail(40L);

      assertThat(detail.messages()).hasSize(1);
      assertThat(detail.messages().get(0).status()).isEqualTo("STOPPED");
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
              new MessagePayload("USER", "hello", null, null)))))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("code", ErrorCode.CONVERSATION_NOT_FOUND.getCode());
    }
  }

  @Nested
  @DisplayName("会话上下文（短期记忆）")
  class ConversationContext {

    @Test
    @DisplayName("返回最近消息正序与滚动摘要")
    void returnsRecentMessagesAndSummary() {
      AgentConversationEntity conversation = new AgentConversationEntity();
      conversation.setId(20L);
      conversation.setSummary("早期摘要");
      when(conversationRepository.findByIdAndUserId(20L, "default"))
          .thenReturn(Optional.of(conversation));

      AgentMessageEntity userMessage = new AgentMessageEntity();
      userMessage.setRole(AgentMessageEntity.MessageRole.USER);
      userMessage.setContent("我目标 Java 后端");
      AgentMessageEntity assistantMessage = new AgentMessageEntity();
      assistantMessage.setRole(AgentMessageEntity.MessageRole.ASSISTANT);
      assistantMessage.setContent("好的，我们开始准备");
      // 仓储按倒序返回最近 N 条，服务层应反转为正序
      when(messageRepository.findByConversationIdOrderByMessageOrderDesc(
          anyLong(), any())).thenReturn(List.of(assistantMessage, userMessage));
      when(messageRepository.countByConversationId(20L)).thenReturn(10L);

      var context = conversationService.getConversationContext(20L, 8);

      assertThat(context.summary()).isEqualTo("早期摘要");
      assertThat(context.totalCount()).isEqualTo(10L);
      assertThat(context.messages()).hasSize(2);
      assertThat(context.messages().get(0).role()).isEqualTo("USER");
      assertThat(context.messages().get(1).role()).isEqualTo("ASSISTANT");
      // 短期记忆不需要终态，避免无谓的 Token 与协议负担
      assertThat(context.messages().get(0).status()).isNull();
    }

    @Test
    @DisplayName("limit 被限制在 1-100 之间")
    void clampsLimit() {
      AgentConversationEntity conversation = new AgentConversationEntity();
      conversation.setId(21L);
      when(conversationRepository.findByIdAndUserId(21L, "default"))
          .thenReturn(Optional.of(conversation));
      when(messageRepository.findByConversationIdOrderByMessageOrderDesc(
          anyLong(), any())).thenReturn(List.of());
      when(messageRepository.countByConversationId(21L)).thenReturn(0L);

      conversationService.getConversationContext(21L, 0);
      conversationService.getConversationContext(21L, 999);

      org.mockito.Mockito.verify(messageRepository, org.mockito.Mockito.times(2))
          .findByConversationIdOrderByMessageOrderDesc(anyLong(), any());
    }

    @Test
    @DisplayName("更新滚动摘要")
    void updatesSummary() {
      AgentConversationEntity conversation = new AgentConversationEntity();
      conversation.setId(22L);
      when(conversationRepository.findByIdAndUserId(22L, "default"))
          .thenReturn(Optional.of(conversation));

      conversationService.updateSummary(22L, "新摘要");

      assertThat(conversation.getSummary()).isEqualTo("新摘要");
      verify(conversationRepository).save(conversation);
    }

    @Test
    @DisplayName("绑定会话活动简历")
    void bindsActiveResume() {
      AgentConversationEntity conversation = new AgentConversationEntity();
      conversation.setId(23L);
      when(conversationRepository.findByIdAndUserId(23L, "default"))
          .thenReturn(Optional.of(conversation));

      conversationService.bindActiveResume(23L, 9L);

      assertThat(conversation.getActiveResumeId()).isEqualTo(9L);
      verify(conversationRepository).save(conversation);
    }

    @Test
    @DisplayName("resumeId 为 null 时解绑活动简历")
    void unbindsActiveResumeWithNull() {
      AgentConversationEntity conversation = new AgentConversationEntity();
      conversation.setId(24L);
      conversation.setActiveResumeId(9L);
      when(conversationRepository.findByIdAndUserId(24L, "default"))
          .thenReturn(Optional.of(conversation));

      conversationService.bindActiveResume(24L, null);

      assertThat(conversation.getActiveResumeId()).isNull();
      verify(conversationRepository).save(conversation);
    }
  }
}
