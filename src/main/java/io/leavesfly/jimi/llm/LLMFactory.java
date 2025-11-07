package io.leavesfly.jimi.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.leavesfly.jimi.config.JimiConfig;
import io.leavesfly.jimi.config.LLMModelConfig;
import io.leavesfly.jimi.config.LLMProviderConfig;
import io.leavesfly.jimi.exception.ConfigException;
import io.leavesfly.jimi.llm.provider.KimiChatProvider;
import io.leavesfly.jimi.llm.provider.OpenAICompatibleChatProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * LLM 工厂服务
 * 负责创建和缓存 LLM 实例
 * <p>
 * 设计原则：
 * 1. 初始化阶段验证配置（Fail-fast）
 * 2. 按模型名称缓存 LLM 实例（避免重复创建）
 * 3. 支持环境变量覆盖配置
 * 4. 使用 Caffeine 高性能缓存（支持过期、统计、淘汰）
 *
 * @author Jimi Team
 */
@Slf4j
@Service
public class LLMFactory {

    private final JimiConfig config;
    private final ObjectMapper objectMapper;

    /**
     * LLM 实例缓存（使用 Caffeine 高性能缓存）
     * <p>
     * 特性：
     * - 最大容量：10个模型实例
     * - 过期策略：30分钟未访问自动过期
     * - 统计功能：缓存命中率、驱逐次数等
     */
    private final Cache<String, LLM> llmCache;

    @Autowired
    public LLMFactory(JimiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;

        // 初始化 Caffeine 缓存
        this.llmCache = Caffeine.newBuilder()
                .maximumSize(10)  // 最多缓存10个模型
                .expireAfterAccess(30, TimeUnit.MINUTES)  // 30分钟未使用则过期
                .recordStats()  // 记录缓存统计
                .removalListener((key, value, cause) -> {
                    log.debug("LLM cache entry removed: key={}, cause={}", key, cause);
                })
                .build();

        // 初始化阶段验证配置
        validateConfiguration();
    }

    /**
     * 验证配置（Fail-fast）
     * 在应用启动时发现配置错误，而非运行时
     */
    private void validateConfiguration() {
        if (config.getDefaultModel() == null || config.getDefaultModel().isEmpty()) {
            log.warn("No default model configured");
            return;
        }

        String defaultModel = config.getDefaultModel();
        LLMModelConfig modelConfig = config.getModels().get(defaultModel);

        if (modelConfig == null) {
            log.warn("Default model '{}' not found in configuration", defaultModel);
            return;
        }

        LLMProviderConfig providerConfig = config.getProviders().get(modelConfig.getProvider());
        if (providerConfig == null) {
            log.warn("Provider '{}' for default model not found in configuration",
                    modelConfig.getProvider());
            return;
        }

        log.info("LLM configuration validated: defaultModel={}, provider={}",
                defaultModel, modelConfig.getProvider());
    }

    /**
     * 获取或创建 LLM 实例
     * 如果已缓存则返回缓存实例，否则创建新实例并缓存
     *
     * @param modelName 模型名称（null 表示使用默认模型）
     * @return LLM 实例，如果配置不完整则返回 null
     */
    public LLM getOrCreateLLM(String modelName) {
        // 确定使用的模型
        String effectiveModelName = modelName != null ? modelName : config.getDefaultModel();

        if (effectiveModelName == null || effectiveModelName.isEmpty()) {
            log.warn("No model specified and no default model configured");
            return null;
        }

        // 使用 Caffeine 的 get 方法，自动处理缓存未命中
        return llmCache.get(effectiveModelName, key -> {
            log.info("LLM cache miss, creating new instance: {}", key);
            return createLLM(key);
        });
    }

    /**
     * 创建 LLM 实例
     */
    private LLM createLLM(String modelName) {
        LLMModelConfig modelConfig = config.getModels().get(modelName);
        if (modelConfig == null) {
            throw new ConfigException("Model not found in config: " + modelName);
        }

        LLMProviderConfig providerConfig = config.getProviders().get(modelConfig.getProvider());
        if (providerConfig == null) {
            throw new ConfigException("Provider not found in config: " + modelConfig.getProvider());
        }

        String apiKey = resolveApiKey(providerConfig);
        String baseUrl = providerConfig.getBaseUrl();
        String model = modelConfig.getModel();

        if ((apiKey == null || apiKey.isEmpty())
                && !providerConfig.getType().equals(LLMProviderConfig.ProviderType.OLLAMA)) {
            log.error("No valid API key configured for model '{}'. " +
                            "Please set API key in config file or environment variable: {}_API_KEY",
                    modelName, providerConfig.getType().toString().toUpperCase());
            throw new ConfigException("Missing API key for provider: " + providerConfig.getType());
        }

        // 创建带环境变量覆盖的 provider config
        LLMProviderConfig effectiveProviderConfig = LLMProviderConfig.builder()
                .type(providerConfig.getType())
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .customHeaders(providerConfig.getCustomHeaders())
                .build();

        // 创建 ChatProvider
        ChatProvider chatProvider = createChatProvider(
                providerConfig.getType(),
                model,
                effectiveProviderConfig
        );

        log.info("Created LLM: provider={}, model={}", providerConfig.getType(), model);

        return LLM.builder()
                .chatProvider(chatProvider)
                .maxContextSize(modelConfig.getMaxContextSize())
                .build();
    }

    /**
     * 创建 ChatProvider 实例
     */
    private ChatProvider createChatProvider(
            LLMProviderConfig.ProviderType type,
            String model,
            LLMProviderConfig config
    ) {
        switch (type) {
            case KIMI:
                return new KimiChatProvider(model, config, objectMapper);

            case DEEPSEEK:
                return new OpenAICompatibleChatProvider(
                        model, config, objectMapper, "DeepSeek");

            case QWEN:
                return new OpenAICompatibleChatProvider(
                        model, config, objectMapper, "Qwen");

            case OLLAMA:
                return new OpenAICompatibleChatProvider(
                        model, config, objectMapper, "Ollama");

            case OPENAI:
                return new OpenAICompatibleChatProvider(
                        model, config, objectMapper, "OpenAI");

            default:
                throw new ConfigException("Unsupported provider type: " + type);
        }
    }

    /**
     * 清除 LLM 缓存
     * 用于配置热更新等场景
     */
    public void clearCache() {
        long count = llmCache.estimatedSize();
        llmCache.invalidateAll();
        log.info("LLM cache cleared: {} instances removed", count);
    }

    /**
     * 获取缓存统计信息
     */
    public String getCacheStats() {
        var stats = llmCache.stats();
        return String.format(
                "LLM Cache - Size: %d, Hits: %.2f%%, Misses: %d, Evictions: %d",
                llmCache.estimatedSize(),
                stats.hitRate() * 100,
                stats.missCount(),
                stats.evictionCount()
        );
    }

    /**
     * 解析 API Key，优先使用环境变量
     * 支持的环境变量格式: {PROVIDER_TYPE}_API_KEY
     * 例如: QWEN_API_KEY, KIMI_API_KEY, DEEPSEEK_API_KEY 等
     */
    private String resolveApiKey(LLMProviderConfig providerConfig) {
        // 获取配置文件中的 API Key
        String configApiKey = providerConfig.getApiKey();

        // 构建环境变量名称
        String envVarName = providerConfig.getType().toString().toUpperCase() + "_API_KEY";
        String envApiKey = System.getenv(envVarName);

        // 优先使用环境变量，如果环境变量不存在或为空，则使用配置文件中的值
        if (envApiKey != null && !envApiKey.isEmpty()) {
            log.debug("Using API key from environment variable: {}", envVarName);
            return envApiKey;
        }

        // 如果配置文件中的值是占位符，返回 null
        if ("xxxx".equals(configApiKey) || configApiKey == null || configApiKey.isEmpty()) {
            log.debug("No valid API key found in config for provider: {}", providerConfig.getType());
            return null;
        }

        log.debug("Using API key from config file for provider: {}", providerConfig.getType());
        return configApiKey;
    }
}
