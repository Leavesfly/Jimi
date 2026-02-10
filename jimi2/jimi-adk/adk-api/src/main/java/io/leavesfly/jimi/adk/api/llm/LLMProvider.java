package io.leavesfly.jimi.adk.api.llm;

/**
 * LLM 提供商 SPI 接口
 * <p>
 * 允许通过 Java SPI 机制注册新的 LLM 提供商，无需修改 LLMFactory 的硬编码 switch-case。
 * 每个提供商实现此接口，声明自己支持的 provider 名称和默认 baseUrl，
 * 并负责创建对应的 {@link ChatProvider} 实例。
 * </p>
 *
 * <p>使用方式：
 * <ol>
 *   <li>实现此接口</li>
 *   <li>在 META-INF/services/io.leavesfly.jimi.adk.api.llm.LLMProvider 中注册实现类</li>
 *   <li>LLMFactory 会自动通过 ServiceLoader 发现并使用</li>
 * </ol>
 *
 * @see ChatProvider
 * @see LLMConfig
 */
public interface LLMProvider {

    /**
     * 判断此提供商是否支持给定的 provider 名称
     *
     * @param providerName provider 名称（如 "openai"、"deepseek"、"qwen" 等）
     * @return 是否支持
     */
    boolean supports(String providerName);

    /**
     * 获取此提供商的默认 Base URL
     * <p>
     * 当 LLMConfig 中未指定 baseUrl 时使用此默认值。
     * </p>
     *
     * @param providerName provider 名称（用于同一实现支持多个别名时区分）
     * @return 默认 Base URL
     */
    String getDefaultBaseUrl(String providerName);

    /**
     * 创建 ChatProvider 实例
     *
     * @param config  LLM 配置（已包含解析后的 API Key）
     * @param baseUrl 最终使用的 Base URL（优先使用配置中的值，否则使用默认值）
     * @return ChatProvider 实例
     */
    ChatProvider createChatProvider(LLMConfig config, String baseUrl);

    /**
     * 获取提供商优先级
     * <p>
     * 当多个提供商都支持同一 provider 名称时，优先级高的生效。
     * 数值越大优先级越高，默认为 0。
     * </p>
     *
     * @return 优先级
     */
    default int getPriority() {
        return 0;
    }
}
