package io.leavesfly.jimi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.engine.compaction.Compaction;
import io.leavesfly.jimi.engine.compaction.SimpleCompaction;
import io.leavesfly.jimi.retrieval.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 向量索引相关组件配置
 * <p>
 * 根据配置自动装配：
 * - EmbeddingProvider
 * - VectorStore
 * - Chunker
 * - RetrievalPipeline
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "jimi.vector-index", name = "enabled", havingValue = "true")
public class VectorIndexConfiguration {

    @Autowired
    private JimiConfig jimiConfig;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 创建嵌入提供者
     */
    @Bean
    public EmbeddingProvider embeddingProvider() {
        VectorIndexConfig config = jimiConfig.getVectorIndex();
        
        String providerType = config.getEmbeddingProvider();
        String embeddingModel = config.getEmbeddingModel();
        int dimension = config.getEmbeddingDimension();
        
        log.info("Creating EmbeddingProvider: type={}, model={}, dimension={}", 
                providerType, embeddingModel, dimension);
        
        switch (providerType.toLowerCase()) {
            case "qwen":
                // 获取qwen提供商配置
                LLMProviderConfig qwenConfig = jimiConfig.getProviders().get("qwen");
                if (qwenConfig == null) {
                    log.error("Qwen provider not configured, falling back to mock");
                    return new MockEmbeddingProvider(dimension, "qwen-fallback");
                }
                return new QwenEmbeddingProvider(embeddingModel, dimension, qwenConfig, objectMapper);
                
            case "mock":
            case "local":
                return new MockEmbeddingProvider(dimension, providerType);
                
            default:
                log.warn("Unknown embedding provider: {}, falling back to mock", providerType);
                return new MockEmbeddingProvider(dimension, "mock");
        }
    }

    /**
     * 创建向量存储
     */
    @Bean
    public VectorStore vectorStore(EmbeddingProvider embeddingProvider) {
        VectorIndexConfig config = jimiConfig.getVectorIndex();
        
        String storageType = config.getStorageType();
        String indexPath = config.getIndexPath();
        
        log.info("Creating VectorStore: type={}, path={}", storageType, indexPath);
        
        VectorStore store;
        
        // 目前只支持内存存储，后续可扩展
        switch (storageType.toLowerCase()) {
            case "memory":
            case "file":
                store = new InMemoryVectorStore(objectMapper);
                break;
            default:
                log.warn("Unknown storage type: {}, falling back to in-memory", storageType);
                store = new InMemoryVectorStore(objectMapper);
        }
        
        // 如果配置了自动加载，尝试加载索引
        if (config.isAutoLoad()) {
            Path path = Paths.get(indexPath);
            store.load(path)
                .doOnSuccess(success -> {
                    if (success) {
                        log.info("Index loaded from: {}", indexPath);
                    } else {
                        log.debug("No existing index found at: {}", indexPath);
                    }
                })
                .doOnError(e -> log.warn("Failed to load index: {}", e.getMessage()))
                .onErrorResume(e -> reactor.core.publisher.Mono.just(false))
                .block();
        }
        
        return store;
    }

    /**
     * 创建分块器
     */
    @Bean
    public Chunker chunker() {
        log.info("Creating Chunker: SimpleChunker");
        return new SimpleChunker();
    }

    /**
     * 创建检索管线
     */
    @Bean
    public RetrievalPipeline retrievalPipeline(VectorStore vectorStore, 
                                               EmbeddingProvider embeddingProvider) {
        VectorIndexConfig config = jimiConfig.getVectorIndex();
        int topK = config.getTopK();
        
        log.info("Creating RetrievalPipeline: topK={}", topK);
        
        return new SimpleRetrievalPipeline(vectorStore, embeddingProvider, topK);
    }

    /**
     * 创建检索增强的压缩器
     * 覆盖默认的SimpleCompaction
     */
    @Bean
    @Primary
    public Compaction compaction(VectorStore vectorStore,
                                EmbeddingProvider embeddingProvider) {
        VectorIndexConfig config = jimiConfig.getVectorIndex();
        
        // 基础压缩器
        Compaction baseCompaction = new SimpleCompaction();
        
        // 检索增强压缩（可通过配置关闭）
        boolean enableRetrievalInCompaction = true; // TODO: 添加到配置
        int compactionTopK = Math.min(config.getTopK(), 3); // 压缩时用较少的片段
        
        log.info("Creating RetrievalAwareCompaction: enabled={}, topK={}", 
                enableRetrievalInCompaction, compactionTopK);
        
        return new RetrievalAwareCompaction(
                baseCompaction,
                vectorStore,
                embeddingProvider,
                compactionTopK,
                enableRetrievalInCompaction
        );
    }
}
