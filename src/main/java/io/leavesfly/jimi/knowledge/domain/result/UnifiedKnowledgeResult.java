package io.leavesfly.jimi.knowledge.domain.result;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 统一知识搜索结果
 * 
 * <p>整合 Graph、Memory、Retrieval、Wiki 四个模块的搜索结果，
 * 提供统一的结果视图。
 * 
 * <p>结果特点：
 * - 分模块展示，便于分析
 * - 包含相关度评分，支持排序
 * - 提供关联信息，支持深度挖掘
 */
@Data
@Builder
public class UnifiedKnowledgeResult {
    
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
     * 原始查询关键词
     */
    private String keyword;
    
    /**
     * Graph 搜索结果
     */
    @Builder.Default
    private GraphSearchResult graphResult = GraphSearchResult.empty();
    
    /**
     * Memory 搜索结果
     */
    @Builder.Default
    private MemorySearchResult memoryResult = MemorySearchResult.empty();
    
    /**
     * Retrieval 搜索结果
     */
    @Builder.Default
    private RetrievalSearchResult retrievalResult = RetrievalSearchResult.empty();
    
    /**
     * Wiki 搜索结果
     */
    @Builder.Default
    private WikiSearchResult wikiResult = WikiSearchResult.empty();
    
    /**
     * 总结果数量
     */
    private int totalResults;
    
    /**
     * 搜索耗时（毫秒）
     */
    private long elapsedMs;
    
    /**
     * 跨模块关联信息
     */
    @Builder.Default
    private List<CrossModuleLink> crossLinks = Collections.emptyList();
    
    /**
     * 创建成功结果
     */
    public static UnifiedKnowledgeResult success(String keyword,
                                                  GraphSearchResult graphResult,
                                                  MemorySearchResult memoryResult,
                                                  RetrievalSearchResult retrievalResult,
                                                  WikiSearchResult wikiResult,
                                                  long elapsedMs) {
        int total = graphResult.getEntityCount() + 
                   memoryResult.getEntryCount() + 
                   retrievalResult.getChunkCount() + 
                   wikiResult.getDocumentCount();
        
        return UnifiedKnowledgeResult.builder()
                .success(true)
                .keyword(keyword)
                .graphResult(graphResult)
                .memoryResult(memoryResult)
                .retrievalResult(retrievalResult)
                .wikiResult(wikiResult)
                .totalResults(total)
                .elapsedMs(elapsedMs)
                .build();
    }
    
    /**
     * 创建错误结果
     */
    public static UnifiedKnowledgeResult error(String message) {
        return UnifiedKnowledgeResult.builder()
                .success(false)
                .errorMessage(message)
                .build();
    }
    
    /**
     * Graph 搜索结果摘要
     */
    @Data
    @Builder
    public static class GraphSearchResult {
        /** 实体列表 */
        @Builder.Default
        private List<GraphResult.GraphEntity> entities = Collections.emptyList();
        
        /** 关系列表 */
        @Builder.Default
        private List<GraphResult.GraphRelation> relations = Collections.emptyList();
        
        /** 实体数量 */
        private int entityCount;
        
        /** 关系数量 */
        private int relationCount;
        
        public static GraphSearchResult empty() {
            return GraphSearchResult.builder()
                    .entityCount(0)
                    .relationCount(0)
                    .build();
        }
        
        public static GraphSearchResult from(GraphResult graphResult) {
            if (graphResult == null || !graphResult.isSuccess()) {
                return empty();
            }
            return GraphSearchResult.builder()
                    .entities(graphResult.getEntities())
                    .relations(graphResult.getRelations())
                    .entityCount(graphResult.getEntities().size())
                    .relationCount(graphResult.getRelations().size())
                    .build();
        }
    }
    
    /**
     * Memory 搜索结果摘要
     */
    @Data
    @Builder
    public static class MemorySearchResult {
        /** 记忆条目列表 */
        @Builder.Default
        private List<MemoryResult.MemoryEntry> entries = Collections.emptyList();
        
        /** 条目数量 */
        private int entryCount;
        
        public static MemorySearchResult empty() {
            return MemorySearchResult.builder()
                    .entryCount(0)
                    .build();
        }
        
        public static MemorySearchResult from(MemoryResult memoryResult) {
            if (memoryResult == null || !memoryResult.isSuccess()) {
                return empty();
            }
            return MemorySearchResult.builder()
                    .entries(memoryResult.getEntries())
                    .entryCount(memoryResult.getEntries().size())
                    .build();
        }
    }
    
    /**
     * Retrieval 搜索结果摘要
     */
    @Data
    @Builder
    public static class RetrievalSearchResult {
        /** 代码片段列表 */
        @Builder.Default
        private List<RetrievalResult.CodeChunkResult> chunks = Collections.emptyList();
        
        /** 片段数量 */
        private int chunkCount;
        
        /** 平均相似度分数 */
        private double avgScore;
        
        public static RetrievalSearchResult empty() {
            return RetrievalSearchResult.builder()
                    .chunkCount(0)
                    .avgScore(0.0)
                    .build();
        }
        
        public static RetrievalSearchResult from(RetrievalResult retrievalResult) {
            if (retrievalResult == null || !retrievalResult.isSuccess()) {
                return empty();
            }
            double avgScore = retrievalResult.getChunks().stream()
                    .mapToDouble(RetrievalResult.CodeChunkResult::getScore)
                    .average()
                    .orElse(0.0);
            
            return RetrievalSearchResult.builder()
                    .chunks(retrievalResult.getChunks())
                    .chunkCount(retrievalResult.getChunks().size())
                    .avgScore(avgScore)
                    .build();
        }
    }
    
    /**
     * Wiki 搜索结果摘要
     */
    @Data
    @Builder
    public static class WikiSearchResult {
        /** 文档列表 */
        @Builder.Default
        private List<WikiResult.WikiDocument> documents = Collections.emptyList();
        
        /** 文档数量 */
        private int documentCount;
        
        public static WikiSearchResult empty() {
            return WikiSearchResult.builder()
                    .documentCount(0)
                    .build();
        }
        
        public static WikiSearchResult from(WikiResult wikiResult) {
            if (wikiResult == null || !wikiResult.isSuccess()) {
                return empty();
            }
            return WikiSearchResult.builder()
                    .documents(wikiResult.getDocuments())
                    .documentCount(wikiResult.getDocuments().size())
                    .build();
        }
    }
    
    /**
     * 跨模块关联信息
     * 例如：代码实体 <-> 相关记忆、代码片段 <-> Wiki文档等
     */
    @Data
    @Builder
    public static class CrossModuleLink {
        /** 源模块类型 */
        private String sourceModule;
        
        /** 源ID */
        private String sourceId;
        
        /** 目标模块类型 */
        private String targetModule;
        
        /** 目标ID */
        private String targetId;
        
        /** 关联类型 */
        private String linkType;
        
        /** 关联强度（0-1） */
        private double strength;
        
        /** 关联描述 */
        private String description;
    }
}
