package io.leavesfly.jimi.knowledge.domain.query;

import lombok.Builder;
import lombok.Data;

/**
 * 混合搜索查询请求
 * 
 * <p>用于HybridSearch模块，融合Graph和Retrieval的能力：
 * - 同时执行结构化搜索和语义搜索
 * - 支持多种融合策略
 * - 结果去重和排序
 */
@Data
@Builder
public class HybridQuery {
    
    /**
     * 查询关键词/文本
     */
    private String keyword;
    
    /**
     * 返回结果数量
     */
    @Builder.Default
    private int topK = 10;
    
    /**
     * 融合策略
     */
    @Builder.Default
    private FusionStrategy strategy = FusionStrategy.RRF;
    
    /**
     * Graph结果权重（0-1）
     */
    @Builder.Default
    private double graphWeight = 0.5;
    
    /**
     * Retrieval结果权重（0-1）
     */
    @Builder.Default
    private double retrievalWeight = 0.5;
    
    /**
     * 是否包含结构化实体（来自Graph）
     */
    @Builder.Default
    private boolean includeStructured = true;
    
    /**
     * 是否包含语义片段（来自Retrieval）
     */
    @Builder.Default
    private boolean includeSemantic = true;
    
    /**
     * 过滤条件（可选）
     */
    private GraphQuery.GraphFilter graphFilter;
    private RetrievalQuery.RetrievalFilter retrievalFilter;
    
    /**
     * 融合策略枚举
     */
    public enum FusionStrategy {
        /** Reciprocal Rank Fusion - 倒数排名融合 */
        RRF,
        
        /** 加权平均 */
        WEIGHTED_AVERAGE,
        
        /** 级联过滤（先Graph后Retrieval） */
        CASCADE_GRAPH_FIRST,
        
        /** 级联过滤（先Retrieval后Graph） */
        CASCADE_RETRIEVAL_FIRST,
        
        /** 交集（两边都命中） */
        INTERSECTION,
        
        /** 并集（任一命中） */
        UNION
    }
}
