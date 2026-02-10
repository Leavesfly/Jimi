package io.leavesfly.jimi.adk.llm.provider;

import io.leavesfly.jimi.adk.api.llm.ChatProvider;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.api.llm.LLMProvider;

import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容协议的 LLM 提供商
 * <p>
 * 支持所有使用 OpenAI 兼容 API 的 LLM 服务，包括：
 * OpenAI、DeepSeek、Kimi/Moonshot、Qwen/DashScope、Ollama 等。
 * </p>
 * <p>
 * 作为默认的 LLMProvider SPI 实现，优先级为 0。
 * 用户可以通过注册更高优先级的 LLMProvider 来覆盖特定 provider 的行为。
 * </p>
 */
public class OpenAICompatibleLLMProvider implements LLMProvider {

    private static final Map<String, String> PROVIDER_BASE_URLS = Map.of(
            "openai", "https://api.openai.com/v1",
            "kimi", "https://api.moonshot.cn/v1",
            "moonshot", "https://api.moonshot.cn/v1",
            "deepseek", "https://api.deepseek.com/v1",
            "qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "ollama", "http://localhost:11434/v1"
    );

    private static final Set<String> SUPPORTED_PROVIDERS = PROVIDER_BASE_URLS.keySet();

    @Override
    public boolean supports(String providerName) {
        if (providerName == null) {
            return false;
        }
        return SUPPORTED_PROVIDERS.contains(providerName.toLowerCase());
    }

    @Override
    public String getDefaultBaseUrl(String providerName) {
        if (providerName == null) {
            return PROVIDER_BASE_URLS.get("openai");
        }
        return PROVIDER_BASE_URLS.getOrDefault(
                providerName.toLowerCase(),
                PROVIDER_BASE_URLS.get("openai"));
    }

    @Override
    public ChatProvider createChatProvider(LLMConfig config, String baseUrl) {
        return new OpenAICompatibleProvider(config, baseUrl);
    }

    @Override
    public int getPriority() {
        return 0;
    }
}
