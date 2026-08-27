package interview.guide.modules.conversation.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.conversation.dto.ConversationDetailDTO;
import interview.guide.modules.conversation.dto.ConversationListItemDTO;
import interview.guide.modules.conversation.dto.CreateConversationRequest;
import interview.guide.modules.conversation.dto.SaveMessagesRequest;
import interview.guide.modules.conversation.service.AgentConversationService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Copilot 对话 API。
 *
 * <p>面向 Copilot Workspace 前端与 Python Agent Service 的会话/消息读写入口。
 * Controller 只做路由与校验，逻辑委托 {@link AgentConversationService}。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/conversations")
@RequiredArgsConstructor
public class AgentConversationController {

  private final AgentConversationService conversationService;

  @PostMapping
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
  public Result<ConversationListItemDTO> createConversation(
      @RequestBody(required = false) CreateConversationRequest request) {
    return Result.success(conversationService.createConversation(request));
  }

  @GetMapping
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 60)
  public Result<List<ConversationListItemDTO>> listConversations() {
    return Result.success(conversationService.listConversations());
  }

  @GetMapping("/{conversationId}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 60)
  public Result<ConversationDetailDTO> getConversationDetail(@PathVariable Long conversationId) {
    return Result.success(conversationService.getConversationDetail(conversationId));
  }

  @PutMapping("/{conversationId}/title")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<Void> renameConversation(
      @PathVariable Long conversationId,
      @RequestBody Map<String, String> body) {
    conversationService.renameConversation(conversationId, body.get("title"));
    return Result.success();
  }

  @PutMapping("/{conversationId}/pin")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<Void> togglePin(@PathVariable Long conversationId) {
    conversationService.togglePin(conversationId);
    return Result.success();
  }

  @DeleteMapping("/{conversationId}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<Void> deleteConversation(@PathVariable Long conversationId) {
    conversationService.deleteConversation(conversationId);
    return Result.success();
  }

  @PostMapping("/{conversationId}/messages")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 60)
  public Result<Void> saveMessages(
      @PathVariable Long conversationId,
      @RequestBody SaveMessagesRequest request) {
    conversationService.saveMessages(conversationId, request);
    return Result.success();
  }
}