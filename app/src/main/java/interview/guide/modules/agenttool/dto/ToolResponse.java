package interview.guide.modules.agenttool.dto;

/**
 * Agent Tool 统一响应信封。
 *
 * <p>{@code data} 为 Tool 的结构化返回结果（DTO），供 Agent Runtime 使用。
 */
public record ToolResponse(String tool, Object data) {}