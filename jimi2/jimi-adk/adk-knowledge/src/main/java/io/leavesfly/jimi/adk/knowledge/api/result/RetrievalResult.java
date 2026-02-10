package io.leavesfly.jimi.adk.knowledge.api.result;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 向量检索查询结果
 * 
 * <p>封装Retrieval模块的语义检索结果，包括：
 * - 代码片段列表
 * - 相似度分数
 * - 检索耗时
 */
@Data
@Builder
public class RetrievalResult {
    
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
     * 原始查询
     */
    private String query;
    
    /**
     * 检索到的代码片段列表
     */
    @Builder.Default
    private List<CodeChunkResult> chunks = Collections.emptyList();
    
    /**
     * 检索耗时（毫秒）
     */
    private long elapsedMs;
    
    /**
     * 索引统计信息
     */
    private IndexStats indexStats;
    
    /**
     * 创建成功结果
     */
    public static RetrievalResult success(String query, List<CodeChunkResult> chunks, long elapsedMs) {
        return RetrievalResult.builder()
                .success(true)
                .query(query)
                .chunks(chunks)
                .elapsedMs(elapsedMs)
                .build();
    }
    
    /**
     * 创建错误结果
     */
    public static RetrievalResult error(String message) {
        return RetrievalResult.builder()
                .success(false)
                .errorMessage(message)
                .build();
    }
    
    /**
     * 代码片段结果
     */
    @Data
    @Builder
    public static class CodeChunkResult {
        /** 片段ID */
        private String id;
        
        /** 代码内容 */
        private String content;
        
        /** 文件路径 */
        private String filePath;
        
        /** 符号名称（类名、方法名等） */
        private String symbol;
        
        /** 起始行号 */
        private int startLine;
        
        /** 结束行号 */
        private int endLine;
        
        /** 编程语言 */
        private String language;
        
        /** 相似度分数（0-1） */
        private double score;
        
        /** 扩展元数据 */
        private Map<String, String> metadata;
    }
    
    /**
     * 索引统计信息
     */
    @Data
    @Builder
    public static class IndexStats {
        /** 片段总数 */
        private int totalChunks;
        
        /** 文件总数 */
        private int totalFiles;
        
        /** 最后更新时间 */
        private long lastUpdated;
        
        /** 索引大小（字节） */
        private long indexSizeBytes;
    }
}
