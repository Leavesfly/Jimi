package io.leavesfly.jimi.adk.api.knowledge.query;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 统一知识搜索查询
 * 
 * <p>整合 Graph、Memory、Retrieval、Wiki 四个模块的查询能力，
 * 提供一站式知识搜索接口。
 * 
 * <p>使用场景：
 * - 需要全局搜索项目知识时
 * - 需要跨模块关联分析时
 * - 需要多维度知识聚合时
 */
@Data
@Builder
public class UnifiedKnowledgeQuery {
    
    /**
     * 搜索关键词（必填）
     * 适用于所有模块的语义搜索
     */
    private String keyword;
    
    /**
     * 项目根目录（可选）
     * 用于限定搜索范围
     */
    private Path projectRoot;
    
    /**
     * 搜索范围配置
     */
    @Builder.Default
    private SearchScope scope = new SearchScope();
    
    /**
     * 结果数量限制
     */
    @Builder.Default
    private ResultLimit limit = new ResultLimit();
    
    /**
     * 过滤条件（可选）
     */
    @Builder.Default
    private FilterOptions filters = new FilterOptions();
    
    /**
     * 排序策略
     */
    @Builder.Default
    private SortStrategy sortStrategy = SortStrategy.RELEVANCE;
    
    /**
     * 搜索范围配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchScope {
        /** 是否搜索代码图谱 */
        @Builder.Default
        private boolean includeGraph = true;
        
        /** 是否搜索长期记忆 */
        @Builder.Default
        private boolean includeMemory = true;
        
        /** 是否搜索向量检索 */
        @Builder.Default
        private boolean includeRetrieval = true;
        
        /** 是否搜索Wiki文档 */
        @Builder.Default
        private boolean includeWiki = true;
        
        /**
         * 创建默认搜索范围（全部启用）
         */
        public static SearchScope all() {
            return SearchScope.builder()
                    .includeGraph(true)
                    .includeMemory(true)
                    .includeRetrieval(true)
                    .includeWiki(true)
                    .build();
        }
        
        /**
         * 创建仅代码相关的搜索范围
         */
        public static SearchScope codeOnly() {
            return SearchScope.builder()
                    .includeGraph(true)
                    .includeMemory(false)
                    .includeRetrieval(true)
                    .includeWiki(false)
                    .build();
        }
    }
    
    /**
     * 结果数量限制
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResultLimit {
        /** Graph 结果数量上限 */
        @Builder.Default
        private int graphLimit = 10;
        
        /** Memory 结果数量上限 */
        @Builder.Default
        private int memoryLimit = 5;
        
        /** Retrieval 结果数量上限 */
        @Builder.Default
        private int retrievalLimit = 10;
        
        /** Wiki 结果数量上限 */
        @Builder.Default
        private int wikiLimit = 5;
    }
    
    /**
     * 过滤条件
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterOptions {
        /** 文件路径模式（支持通配符） */
        private List<String> filePatterns;
        
        /** 实体类型过滤（CLASS, METHOD, FIELD等） */
        private List<String> entityTypes;
        
        /** 最小相似度阈值（0-1） */
        @Builder.Default
        private double minScore = 0.0;
        
        /** 排除的路径模式 */
        @Builder.Default
        private List<String> excludePatterns = Collections.emptyList();
    }
    
    /**
     * 排序策略
     */
    public enum SortStrategy {
        /** 按相关度排序（默认） */
        RELEVANCE,
        
        /** 按时间排序（最新优先） */
        TIME_DESC,
        
        /** 按文件路径排序 */
        PATH,
        
        /** 按类型分组排序 */
        TYPE
    }
}
