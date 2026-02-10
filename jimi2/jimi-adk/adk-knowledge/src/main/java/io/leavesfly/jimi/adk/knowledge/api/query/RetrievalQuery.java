package io.leavesfly.jimi.adk.knowledge.api.query;

import lombok.Builder;
import lombok.Data;

/**
 * 向量检索查询请求
 * 
 * <p>用于Retrieval模块的语义检索，支持：
 * - 自然语言查询
 * - 相似代码搜索
 * - 多条件过滤
 */
@Data
@Builder
public class RetrievalQuery {
    
    /**
     * 查询文本（自然语言或代码片段）
     */
    private String query;
    
    /**
     * 返回结果数量
     */
    @Builder.Default
    private int topK = 5;
    
    /**
     * 相似度阈值（0-1，低于此值的结果将被过滤）
     */
    @Builder.Default
    private double minScore = 0.0;
    
    /**
     * 过滤条件
     */
    private RetrievalFilter filter;
    
    /**
     * 是否包含代码内容（默认true，可设为false仅返回元数据）
     */
    @Builder.Default
    private boolean includeContent = true;
    
    /**
     * 检索过滤条件
     */
    @Data
    @Builder
    public static class RetrievalFilter {
        /** 语言过滤 */
        private String language;
        
        /** 文件路径模式 */
        private String filePattern;
        
        /** 符号名称模式 */
        private String symbolPattern;
        
        /** 最小更新时间 */
        private Long minUpdatedAt;
    }
}
