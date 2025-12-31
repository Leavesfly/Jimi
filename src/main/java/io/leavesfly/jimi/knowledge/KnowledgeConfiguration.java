package io.leavesfly.jimi.knowledge;

import io.leavesfly.jimi.config.info.GraphConfig;
import io.leavesfly.jimi.config.info.MemoryConfig;
import io.leavesfly.jimi.config.info.VectorIndexConfig;
import io.leavesfly.jimi.knowledge.graph.GraphManager;
import io.leavesfly.jimi.knowledge.graph.GraphServiceImpl;
import io.leavesfly.jimi.knowledge.memory.MemoryManager;
import io.leavesfly.jimi.knowledge.memory.MemoryServiceImpl;
import io.leavesfly.jimi.knowledge.rag.*;
import io.leavesfly.jimi.knowledge.spi.*;
import io.leavesfly.jimi.knowledge.wiki.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knowledge 模块 Spring 配置类
 * 
 * <p>负责组装所有知识模块的服务组件，实现依赖注入。
 * 
 * <p>分层架构：
 * <pre>
 *  Layer 3:    WikiService              (应用层)
 *                  ↓
 *  Layer 2:  MemoryService  HybridSearchService  (核心层)
 *                  ↓              ↓
 *  Layer 1:  GraphService  RagService     (基础层，平行)
 *                  ↓              ↓
 *  Layer 0:  GraphStore    VectorStore          (基础设施层)
 * </pre>
 * 
 * <p>对外提供唯一入口：{@link KnowledgeService}
 */
@Slf4j
@Configuration
public class KnowledgeConfiguration {
    
    // ==================== Layer 1: 基础层服务 ====================
    
    /**
     * Graph 服务（适配 GraphManager）
     */
    @Bean
    public GraphService graphService(
            @Autowired GraphManager graphManager,
            @Autowired GraphConfig graphConfig) {
        log.info("创建 GraphService, enabled={}", graphConfig.getEnabled());
        return new GraphServiceImpl(graphManager, graphConfig);
    }
    
    /**
     * Retrieval 服务（适配 VectorStore 和 EmbeddingProvider）
     */
    @Bean
    public RagService retrievalService(
            @Autowired VectorStore vectorStore,
            @Autowired EmbeddingProvider embeddingProvider,
            @Autowired Chunker chunker,
            @Autowired VectorIndexConfig vectorIndexConfig) {
        log.info("创建 RagService, enabled={}", vectorIndexConfig.isEnabled());
        return new RagServiceImpl(vectorStore, embeddingProvider, chunker, vectorIndexConfig);
    }
    
    // ==================== Layer 2: 核心层服务 ====================
    
    /**
     * Memory 服务（适配 MemoryManager）
     */
    @Bean
    public MemoryService memoryService(
            @Autowired MemoryManager memoryManager,
            @Autowired MemoryConfig memoryConfig) {
        log.info("创建 MemoryService, longTermEnabled={}", memoryConfig.isLongTermEnabled());
        return new MemoryServiceImpl(memoryManager, memoryConfig);
    }
    
    /**
     * HybridSearch 服务（组合 Graph 和 Retrieval）
     */
    @Bean
    public HybridSearchService hybridSearchService(
            @Autowired GraphService graphService,
            @Autowired RagService retrievalService) {
        log.info("创建 HybridSearchService");
        return new HybridSearchServiceImpl(graphService, retrievalService);
    }
    
    // ==================== Layer 3: 应用层服务 ====================
    
    /**
     * Wiki 服务（适配 WikiGenerator）
     */
    @Bean
    public WikiService wikiService(
            @Autowired WikiGenerator wikiGenerator,
            @Autowired ChangeDetector changeDetector,
            @Autowired WikiValidator wikiValidator,
            @Autowired(required = false) WikiIndexManager wikiIndexManager) {
        log.info("创建 WikiService");
        return new WikiServiceImpl(wikiGenerator, changeDetector, wikiValidator, wikiIndexManager);
    }
    
    // ==================== 统一门面 ====================
    
    /**
     * KnowledgeService 统一门面
     * 对外提供知识模块的唯一入口
     */
    @Bean
    public KnowledgeService knowledgeService(
            @Autowired GraphService graphService,
            @Autowired RagService retrievalService,
            @Autowired MemoryService memoryService,
            @Autowired HybridSearchService hybridSearchService,
            @Autowired WikiService wikiService) {
        log.info("创建 KnowledgeService（统一门面）");
        return new KnowledgeServiceImpl(
                graphService, 
                retrievalService, 
                memoryService, 
                hybridSearchService, 
                wikiService);
    }
}
