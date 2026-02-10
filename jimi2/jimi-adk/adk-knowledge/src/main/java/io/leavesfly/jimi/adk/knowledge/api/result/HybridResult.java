package io.leavesfly.jimi.adk.knowledge.api.result;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 混合搜索查询结果
 * 
 * <p>封装HybridSearch模块的融合结果，包括：
 * - 来自Graph的结构化实体
 * - 来自Retrieval的语义片段
 * - 融合后的综合结果
 */
@Data
@Builder
public class HybridResult {
    
    /**
     * 查询是否成功
     */
    @Builder.Default
    private boolean success = true;
    
    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;
    
    /**
     * 查询关键词
     */
    private String keyword;
    
    /**
     * 使用的融合策略
     */
    private String fusionStrategy;
    
    /**
     * 结构化实体列表（来自Graph）
     */
    @Builder.Default
    private List<GraphResult.GraphEntity> structuredEntities = Collections.emptyList();
    
    /**
     * 语义代码片段列表（来自Retrieval）
     */
    @Builder.Default
    private List<RetrievalResult.CodeChunkResult> semanticChunks = Collections.emptyList();
    
    /**
     * 融合后的统一结果（按相关度排序）
     */
    @Builder.Default
    private List<HybridItem> mergedResults = Collections.emptyList();
    
    /**
     * Graph查询耗时（毫秒）
     */
    private long graphElapsedMs;
    
    /**
     * Retrieval查询耗时（毫秒）
     */
    private long retrievalElapsedMs;
    
    /**
     * 总耗时（毫秒）
     */
    private long totalElapsedMs;
    
    /**
     * 创建成功结果
     */
    public static HybridResult success(
            String keyword,
            List<GraphResult.GraphEntity> entities,
            List<RetrievalResult.CodeChunkResult> chunks,
            List<HybridItem> merged) {
        return HybridResult.builder()
                .success(true)
                .keyword(keyword)
                .structuredEntities(entities)
                .semanticChunks(chunks)
                .mergedResults(merged)
                .build();
    }
    
    /**
     * 创建错误结果
     */
    public static HybridResult error(String message) {
        return HybridResult.builder()
                .success(false)
                .errorMessage(message)
                .build();
    }
    
    /**
     * 融合后的统一结果项
     */
    @Data
    @Builder
    public static class HybridItem {
        /** 来源：GRAPH 或 RETRIEVAL */
        private String source;
        
        /** 实体/片段ID */
        private String id;
        
        /** 名称或标题 */
        private String name;
        
        /** 内容摘要 */
        private String content;
        
        /** 文件路径 */
        private String filePath;
        
        /** 行号范围 */
        private int startLine;
        private int endLine;
        
        /** 融合后的分数 */
        private double score;
        
        /** 在Graph结果中的排名（-1表示不在） */
        private int graphRank;
        
        /** 在Retrieval结果中的排名（-1表示不在） */
        private int retrievalRank;
    }
}
