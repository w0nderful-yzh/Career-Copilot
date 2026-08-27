package interview.guide.modules.agenttool.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.agenttool.dto.ToolInfoDTO;
import interview.guide.modules.agenttool.dto.ToolRequest;
import interview.guide.modules.agenttool.dto.ToolResponse;
import interview.guide.modules.agenttool.service.AgentToolService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Tool API。
 *
 * <p>面向 Python Agent Runtime 的统一业务能力入口。
 * Controller 只做路由、限流与请求校验，逻辑委托给 {@link AgentToolService}。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AgentToolController {

  private final AgentToolService agentToolService;

  /** Tool Discovery：返回全部可用 Tool 的名称、描述、权限与参数 schema */
  @GetMapping("/api/agent/tools")
  public Result<List<ToolInfoDTO>> listTools() {
    return Result.success(agentToolService.listTools());
  }

  /**
   * Tool 统一执行入口：所有业务能力都经由该端点调用。
   * 请求体可选（无参数的 Tool 可传空 body），限流防止 Agent 高频调用。
   */
  @PostMapping("/api/agent/tools/{toolName}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 20)
  public Result<ToolResponse> executeTool(
      @PathVariable String toolName,
      @RequestBody(required = false) ToolRequest request) {
    return Result.success(agentToolService.execute(
        toolName, request == null ? null : request.arguments()));
  }
}