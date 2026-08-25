package interview.guide.modules.llmprovider.dto;

/**
 * 默认 Provider 选择结果：聊天、向量、Agent 三种用途。
 */
public record DefaultProviderDTO(
    String defaultProvider,
    String defaultEmbeddingProvider,
    String defaultAgentProvider
) {
    public DefaultProviderDTO(String defaultProvider) {
        this(defaultProvider, null, null);
    }
}