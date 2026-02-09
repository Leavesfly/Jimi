package io.leavesfly.jimi.adk.api.knowledge.spi;

import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.knowledge.query.HybridQuery;
import io.leavesfly.jimi.adk.api.knowledge.result.HybridResult;
import reactor.core.publisher.Mono;

/**
 * 混合搜索服务接口（Layer 2 - 核心层）
 * 
 * <p>职责：
 * - 同时调用 GraphService 和 RagService
 * - 融合结构化搜索和语义搜索结果
 * - 支持多种融合策略（RRF、加权平均等）
 * 
 * <p>架构定位：
 * - 依赖 GraphService 和 RagService（Layer 1）
 * - 作为 Graph 和 Retrieval 的组合器
 * - 为上层服务提供统一的混合搜索能力
 * 
 * @see GraphService 提供结构化搜索结果
 * @see RagService 提供语义搜索结果
 */
public interface HybridSearchService {
    
    /**
     * 初始化混合搜索服务
     */
    Mono<Boolean> initialize(Runtime runtime);
    
    /**
     * 执行混合搜索
     */
    Mono<HybridResult> search(HybridQuery query);
    
    /**
     * 仅执行 Graph 搜索
     */
    Mono<HybridResult> searchGraphOnly(HybridQuery query);
    
    /**
     * 仅执行 Retrieval 搜索
     */
    Mono<HybridResult> searchRetrievalOnly(HybridQuery query);
    
    /**
     * 检查混合搜索功能是否启用
     */
    boolean isEnabled();
}
