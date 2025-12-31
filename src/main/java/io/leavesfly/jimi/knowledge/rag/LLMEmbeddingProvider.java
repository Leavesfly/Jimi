package io.leavesfly.jimi.knowledge.rag;

import io.leavesfly.jimi.llm.ChatProvider;
import io.leavesfly.jimi.llm.LLM;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 基于LLM的嵌入提供者
 * <p>
 * 复用现有LLM提供商的嵌入API，支持：
 * - OpenAI Embedding API
 * - 通义千问 Embedding API
 * - 其他兼容提供商
 * <p>
 * 优势：
 * - 复用LLM配置和认证
 * - 统一API调用管理
 * - 支持批量嵌入
 */
@Slf4j
public class LLMEmbeddingProvider implements EmbeddingProvider {

    private final LLM llm;
    private final String embeddingModel;
    private final int dimension;

    /**
     * 构造函数
     *
     * @param llm LLM实例
     * @param embeddingModel 嵌入模型名称（如：text-embedding-ada-002、text-embedding-v1）
     * @param dimension 向量维度
     */
    public LLMEmbeddingProvider(LLM llm, String embeddingModel, int dimension) {
        this.llm = llm;
        this.embeddingModel = embeddingModel;
        this.dimension = dimension;
        log.info("Initialized LLMEmbeddingProvider: model={}, dimension={}", embeddingModel, dimension);
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    @Override
    public Mono<float[]> embed(String text) {
        return Mono.defer(() -> {
            ChatProvider provider = llm.getChatProvider();
            
            // 检查提供商是否支持嵌入
            if (!supportsEmbedding(provider)) {
                log.warn("Provider {} does not support embedding API, falling back to mock", 
                        provider.getClass().getSimpleName());
                return useMockEmbedding(text);
            }

            try {
                // 调用提供商的嵌入API
                // 注意：这里需要提供商实现嵌入接口
                // 当前暂时使用mock实现，后续扩展提供商接口
                return callEmbeddingAPI(provider, text);
            } catch (Exception e) {
                log.error("Failed to call embedding API, falling back to mock", e);
                return useMockEmbedding(text);
            }
        });
    }

    @Override
    public Mono<List<float[]>> embedBatch(List<String> texts) {
        return Mono.defer(() -> {
            ChatProvider provider = llm.getChatProvider();
            
            if (!supportsEmbedding(provider)) {
                return Mono.just(texts).flatMapIterable(list -> list)
                        .flatMap(this::useMockEmbedding)
                        .collectList();
            }

            try {
                return callBatchEmbeddingAPI(provider, texts);
            } catch (Exception e) {
                log.error("Failed to call batch embedding API, falling back to sequential", e);
                return Mono.just(texts).flatMapIterable(list -> list)
                        .flatMap(this::embed)
                        .collectList();
            }
        });
    }

    @Override
    public String getProviderName() {
        return String.format("llm-%s", embeddingModel);
    }

    /**
     * 检查提供商是否支持嵌入
     */
    private boolean supportsEmbedding(ChatProvider provider) {
        // TODO: 检查提供商是否实现EmbeddingCapable接口
        // 当前版本暂时返回false，后续扩展
        return false;
    }

    /**
     * 调用嵌入API
     */
    private Mono<float[]> callEmbeddingAPI(ChatProvider provider, String text) {
        // TODO: 实现实际的API调用
        // 这里需要扩展ChatProvider接口，添加embedding方法
        // 或创建单独的EmbeddingProvider接口
        
        log.debug("Calling embedding API for text length: {}", text.length());
        
        // 临时实现：返回mock向量
        return useMockEmbedding(text);
    }

    /**
     * 批量调用嵌入API
     */
    private Mono<List<float[]>> callBatchEmbeddingAPI(ChatProvider provider, List<String> texts) {
        // TODO: 实现批量API调用
        log.debug("Calling batch embedding API for {} texts", texts.size());
        
        // 临时实现：顺序调用
        return Mono.just(texts).flatMapIterable(list -> list)
                .flatMap(text -> callEmbeddingAPI(provider, text))
                .collectList();
    }

    /**
     * 降级到mock嵌入
     */
    private Mono<float[]> useMockEmbedding(String text) {
        MockEmbeddingProvider mockProvider = new MockEmbeddingProvider(dimension, "llm-fallback");
        return mockProvider.embed(text);
    }
}
