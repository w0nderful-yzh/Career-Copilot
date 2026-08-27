package interview.guide.modules.agenttool.dto;

import java.util.Map;

/**
 * Agent Tool 统一请求信封。
 *
 * <p>{@code arguments} 为 Tool 参数的扁平结构，由 Agent Runtime 传入。
 */
public record ToolRequest(Map<String, Object> arguments) {

  public ToolRequest {
    if (arguments == null) {
      arguments = Map.of();
    }
  }
}