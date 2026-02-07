package io.leavesfly.jimi.adk.llm;

import io.leavesfly.jimi.adk.api.llm.ChatProvider;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.llm.provider.OpenAICompatibleProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * LLM 工厂
 * 负责创建和缓存 LLM 实例
 */
@Slf4j
public class LLMFactory {
    
    /**
     * LLM 实例缓存
     */
    private final Cache<String, LLM> llmCache;
    
    public LLMFactory() {
        this.llmCache = Caffeine.newBuilder()
                .maximumSize(10)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }
    
    /**
     * 创建 LLM 实例
     */
    public LLM create(LLMConfig config) {
        String cacheKey = buildCacheKey(config);
        
        return llmCache.get(cacheKey, key -> {
            log.info("创建 LLM: provider={}, model={}", config.getProvider(), config.getModel());
            
            // 解析 API Key（支持环境变量）
            String apiKey = resolveApiKey(config);
            
            // 创建配置副本并设置解析后的 API Key
            LLMConfig resolvedConfig = LLMConfig.builder()
                    .provider(config.getProvider())
                    .model(config.getModel())
                    .apiKey(apiKey)
                    .baseUrl(config.getBaseUrl())
                    .temperature(config.getTemperature())
                    .maxTokens(config.getMaxTokens())
                    .connectTimeout(config.getConnectTimeout())
                    .readTimeout(config.getReadTimeout())
                    .build();
            
            ChatProvider chatProvider = createChatProvider(resolvedConfig);
            return new DefaultLLM(resolvedConfig, chatProvider);
        });
    }
    
    /**
     * 创建聊天提供者
     */
    private ChatProvider createChatProvider(LLMConfig config) {
        String provider = config.getProvider().toLowerCase();
        
        // 目前所有提供商都使用 OpenAI 兼容接口
        // 后续可以根据 provider 创建不同的实现
        switch (provider) {
            case "kimi":
            case "moonshot":
                return new OpenAICompatibleProvider(config, 
                        config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.moonshot.cn/v1");
            case "deepseek":
                return new OpenAICompatibleProvider(config,
                        config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.deepseek.com/v1");
            case "qwen":
            case "dashscope":
                return new OpenAICompatibleProvider(config,
                        config.getBaseUrl() != null ? config.getBaseUrl() : "https://dashscope.aliyuncs.com/compatible-mode/v1");
            case "ollama":
                return new OpenAICompatibleProvider(config,
                        config.getBaseUrl() != null ? config.getBaseUrl() : "http://localhost:11434/v1");
            case "openai":
            default:
                return new OpenAICompatibleProvider(config,
                        config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.openai.com/v1");
        }
    }
    
    /**
     * 解析 API Key
     * 优先从环境变量读取
     */
    private String resolveApiKey(LLMConfig config) {
        String provider = config.getProvider().toUpperCase();
        
        // 尝试从环境变量获取
        String envKey = provider + "_API_KEY";
        String apiKey = System.getenv(envKey);
        
        if (apiKey != null && !apiKey.isEmpty()) {
            log.debug("使用环境变量 {} 中的 API Key", envKey);
            return apiKey;
        }
        
        // 回退到配置文件中的值
        return config.getApiKey();
    }
    
    /**
     * 构建缓存键
     */
    private String buildCacheKey(LLMConfig config) {
        return config.getProvider() + ":" + config.getModel();
    }
    
    /**
     * 获取缓存统计
     */
    public String getCacheStats() {
        return llmCache.stats().toString();
    }
    
    /**
     * 清空缓存
     */
    public void clearCache() {
        llmCache.invalidateAll();
        log.info("LLM 缓存已清空");
    }
}
