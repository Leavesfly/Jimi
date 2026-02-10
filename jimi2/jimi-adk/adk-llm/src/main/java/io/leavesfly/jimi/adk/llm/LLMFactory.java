package io.leavesfly.jimi.adk.llm;

import io.leavesfly.jimi.adk.api.llm.ChatProvider;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.api.llm.LLMProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

/**
 * LLM 工厂
 * <p>
 * 负责创建和缓存 LLM 实例。通过 Java SPI 机制（{@link LLMProvider}）
 * 自动发现并使用合适的 LLM 提供商，无需硬编码 switch-case。
 * </p>
 * <p>
 * 扩展新的 LLM 提供商只需：
 * <ol>
 *   <li>实现 {@link LLMProvider} 接口</li>
 *   <li>在 META-INF/services/io.leavesfly.jimi.adk.api.llm.LLMProvider 中注册</li>
 * </ol>
 * </p>
 */
@Slf4j
public class LLMFactory {
    
    /**
     * LLM 实例缓存
     */
    private final Cache<String, LLM> llmCache;

    /**
     * 通过 SPI 加载的 LLM 提供商列表（按优先级降序排列）
     */
    private final List<LLMProvider> providers;
    
    public LLMFactory() {
        this.llmCache = Caffeine.newBuilder()
                .maximumSize(10)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .recordStats()
                .build();
        this.providers = loadProviders();
    }

    /**
     * 通过 SPI 加载所有 LLMProvider 实现，按优先级降序排列
     */
    private static List<LLMProvider> loadProviders() {
        List<LLMProvider> loaded = new ArrayList<>();
        for (LLMProvider provider : ServiceLoader.load(LLMProvider.class)) {
            loaded.add(provider);
            log.debug("已加载 LLMProvider: {}", provider.getClass().getName());
        }
        loaded.sort(Comparator.comparingInt(LLMProvider::getPriority).reversed());
        log.info("已加载 {} 个 LLMProvider", loaded.size());
        return loaded;
    }
    
    /**
     * 创建 LLM 实例
     * <p>
     * 注意：传入的 LLMConfig 应已包含解析后的 API Key（由 JimiConfig.toLLMConfig() 负责解析），
     * 本方法不再重复解析环境变量。如需直接使用未解析的配置，请先通过 JimiConfig 处理。
     * </p>
     */
    public LLM create(LLMConfig config) {
        String cacheKey = buildCacheKey(config);
        
        return llmCache.get(cacheKey, key -> {
            log.info("创建 LLM: provider={}, model={}", config.getProvider(), config.getModel());
            
            ChatProvider chatProvider = createChatProvider(config);
            return new DefaultLLM(config, chatProvider);
        });
    }
    
    /**
     * 创建聊天提供者
     * <p>
     * 通过 SPI 查找支持当前 provider 的 LLMProvider，优先级高的优先匹配。
     * 如果没有任何 SPI 提供商匹配，则抛出异常。
     * </p>
     */
    private ChatProvider createChatProvider(LLMConfig config) {
        String providerName = config.getProvider();

        for (LLMProvider provider : providers) {
            if (provider.supports(providerName)) {
                String baseUrl = config.getBaseUrl() != null
                        ? config.getBaseUrl()
                        : provider.getDefaultBaseUrl(providerName);
                log.debug("使用 LLMProvider: {} (priority={})",
                        provider.getClass().getSimpleName(), provider.getPriority());
                return provider.createChatProvider(config, baseUrl);
            }
        }

        throw new IllegalArgumentException(
                "不支持的 LLM 提供商: " + providerName
                        + "。请通过 SPI 注册对应的 LLMProvider 实现。");
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
