package io.leavesfly.jimi.adk.knowledge.api.query;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 代码图谱查询请求
 * 
 * <p>用于Graph模块的结构化查询，支持多种查询类型：
 * - 符号搜索：按类名、方法名等精确/模糊匹配
 * - 依赖分析：查询实体的依赖关系
 * - 影响分析：分析代码变更的影响范围
 * - 调用链分析：追踪方法调用关系
 */
@Data
@Builder
public class GraphQuery {
    
    /**
     * 查询类型
     */
    private QueryType type;
    
    /**
     * 查询关键词（用于符号搜索、模糊匹配等）
     */
    private String keyword;
    
    /**
     * 实体ID（用于关系查询、影响分析等）
     */
    private String entityId;
    
    /**
     * 最大返回结果数
     */
    @Builder.Default
    private int limit = 10;
    
    /**
     * 最大遍历深度（用于调用链、依赖分析）
     */
    @Builder.Default
    private int maxDepth = 3;
    
    /**
     * 过滤条件
     */
    private GraphFilter filter;
    
    /**
     * 是否生成Mermaid图
     */
    @Builder.Default
    private boolean generateDiagram = false;
    
    /**
     * 查询类型枚举
     */
    public enum QueryType {
        /** 按符号名搜索 */
        SEARCH_BY_SYMBOL,
        
        /** 查询依赖关系（我依赖谁） */
        FIND_DEPENDENCIES,
        
        /** 查询被依赖关系（谁依赖我） */
        FIND_DEPENDENTS,
        
        /** 影响范围分析 */
        IMPACT_ANALYSIS,
        
        /** 调用链分析 */
        CALL_CHAIN,
        
        /** 继承层次分析 */
        INHERITANCE_TREE,
        
        /** 获取实体详情 */
        GET_ENTITY_DETAIL
    }
    
    /**
     * 图谱过滤条件
     */
    @Data
    @Builder
    public static class GraphFilter {
        /** 语言过滤（如："java"） */
        private String language;
        
        /** 包名模式（如："io.leavesfly.*"） */
        private String packagePattern;
        
        /** 文件路径模式 */
        private String filePattern;
        
        /** 实体类型（CLASS, METHOD, FIELD等） */
        private List<String> entityTypes;
        
        /** 排除的包名模式 */
        private List<String> excludePatterns;
    }
}
