package io.leavesfly.jimi.adk.knowledge.api.query;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.List;

/**
 * Wiki文档查询请求
 * 
 * <p>用于Wiki模块的智能文档生成，支持：
 * - 生成单个/批量文档
 * - 搜索文档内容
 * - 检测变更并更新
 * - 验证文档质量
 */
@Data
@Builder
public class WikiQuery {
    
    /**
     * 操作类型
     */
    private OperationType operation;
    
    /**
     * 文档标题（用于生成和搜索）
     */
    private String title;
    
    /**
     * 搜索关键词
     */
    private String keyword;
    
    /**
     * 项目根目录（用于生成和检测变更）
     */
    private Path projectRoot;
    
    /**
     * 目标主题列表（用于批量生成）
     */
    private List<String> topics;
    
    /**
     * 是否包含图表
     */
    @Builder.Default
    private boolean includeDiagrams = true;
    
    /**
     * 最大返回结果数（用于搜索）
     */
    @Builder.Default
    private int limit = 10;
    
    /**
     * 输出格式
     */
    @Builder.Default
    private OutputFormat outputFormat = OutputFormat.MARKDOWN;
    
    /**
     * 操作类型枚举
     */
    public enum OperationType {
        /** 生成单个文档 */
        GENERATE,
        
        /** 批量生成文档 */
        BATCH_GENERATE,
        
        /** 搜索文档 */
        SEARCH,
        
        /** 检测变更并更新 */
        DETECT_AND_UPDATE,
        
        /** 验证文档质量 */
        VALIDATE
    }
    
    /**
     * 输出格式枚举
     */
    public enum OutputFormat {
        MARKDOWN,
        HTML,
        JSON
    }
}
