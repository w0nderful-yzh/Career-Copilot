package interview.guide.modules.agenttool.dto;

import interview.guide.modules.agenttool.model.AgentToolPermission;

/**
 * Agent Tool 元信息，用于 Tool Discovery。
 *
 * <p>{@code inputSchema} 为参数的人类可读描述，帮助 LLM 生成正确参数。
 */
public record ToolInfoDTO(
    String name,
    String description,
    AgentToolPermission permission,
    String inputSchema) {}