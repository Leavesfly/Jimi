package io.leavesfly.jimi.adk.api.knowledge.result;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Wiki文档查询结果
 * 
 * <p>封装Wiki模块的操作结果，包括：
 * - 生成的文档内容
 * - 搜索结果
 * - 变更检测结果
 * - 验证结果
 */
@Data
@Builder
public class WikiResult {
    
    /**
     * 操作是否成功
     */
    @Builder.Default
    private boolean success = true;
    
    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;
    
    /**
     * 生成的文档内容（Markdown格式）
     */
    private String content;
    
    /**
     * 文档标题
     */
    private String title;
    
    /**
     * 文档存储路径
     */
    private Path documentPath;
    
    /**
     * 搜索结果列表
     */
    @Builder.Default
    private List<WikiDocument> documents = Collections.emptyList();
    
    /**
     * 变更检测结果
     */
    @Builder.Default
    private List<FileChange> changes = Collections.emptyList();
    
    /**
     * 验证结果
     */
    private ValidationResult validation;
    
    /**
     * 创建成功结果（生成文档）
     */
    public static WikiResult success(String title, String content, Path path) {
        return WikiResult.builder()
                .success(true)
                .title(title)
                .content(content)
                .documentPath(path)
                .build();
    }
    
    /**
     * 创建错误结果
     */
    public static WikiResult error(String message) {
        return WikiResult.builder()
                .success(false)
                .errorMessage(message)
                .build();
    }
    
    /**
     * Wiki文档信息
     */
    @Data
    @Builder
    public static class WikiDocument {
        /** 文档标题 */
        private String title;
        
        /** 文档路径 */
        private Path path;
        
        /** 内容摘要 */
        private String summary;
        
        /** 相关度分数 */
        private double relevanceScore;
        
        /** 最后更新时间 */
        private long lastUpdated;
    }
    
    /**
     * 文件变更信息
     */
    @Data
    @Builder
    public static class FileChange {
        /** 文件路径 */
        private Path filePath;
        
        /** 变更类型：ADDED, MODIFIED, DELETED */
        private String changeType;
        
        /** 是否需要更新文档 */
        private boolean needsUpdate;
    }
    
    /**
     * 验证结果
     */
    @Data
    @Builder
    public static class ValidationResult {
        /** 是否通过验证 */
        private boolean valid;
        
        /** 问题列表 */
        private List<String> issues;
        
        /** 建议列表 */
        private List<String> suggestions;
    }
}
