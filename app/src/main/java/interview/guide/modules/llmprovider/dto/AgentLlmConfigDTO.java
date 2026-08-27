package interview.guide.modules.llmprovider.dto;

/**
 * Agent Service 的 LLM 连接配置。
 *
 * <p>供 Python Agent Runtime 初始化模型客户端使用，包含 baseUrl 与解密后的 apiKey。
 * 该接口仅用于内网可信的服务间调用（Agent Runtime → Java Backend）。
 */
public record AgentLlmConfigDTO(
    String providerId,
    String baseUrl,
    String model,
    String apiKey) {}