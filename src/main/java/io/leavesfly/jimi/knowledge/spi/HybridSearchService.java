package io.leavesfly.jimi.knowledge.spi;

import io.leavesfly.jimi.core.engine.runtime.Runtime;
import io.leavesfly.jimi.knowledge.domain.query.HybridQuery;
import io.leavesfly.jimi.knowledge.domain.result.HybridResult;
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
 * <p>设计目的：
 * 避免 Graph 和 Retrieval 之间产生依赖，
 * 将混合搜索的职责提升到上层，保持底层模块的独立性
 * 
 * @see GraphService 提供结构化搜索结果
 * @see RagService 提供语义搜索结果
 */
public interface HybridSearchService {
    
    /**
     * 初始化混合搜索服务
     * 
     * @param runtime 运行时环境
     * @return 初始化结果
     */
    Mono<Boolean> initialize(Runtime runtime);
    
    /**
     * 执行混合搜索
     * 
     * @param query 混合查询请求
     * @return 融合后的搜索结果
     */
    Mono<HybridResult> search(HybridQuery query);
    
    /**
     * 仅执行 Graph 搜索
     * 
     * @param query 混合查询请求（仅使用其中的 Graph 相关参数）
     * @return Graph 搜索结果
     */
    Mono<HybridResult> searchGraphOnly(HybridQuery query);
    
    /**
     * 仅执行 Retrieval 搜索
     * 
     * @param query 混合查询请求（仅使用其中的 Retrieval 相关参数）
     * @return Retrieval 搜索结果
     */
    Mono<HybridResult> searchRetrievalOnly(HybridQuery query);
    
    /**
     * 检查混合搜索功能是否启用
     * （需要 Graph 和 Retrieval 至少一个启用）
     * 
     * @return 是否启用
     */
    boolean isEnabled();
}
