package io.leavesfly.jimi.knowledge.domain.result;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 代码图谱查询结果
 * 
 * <p>封装Graph模块的查询结果，包括：
 * - 实体列表
 * - 关系列表
 * - 可视化Mermaid代码
 * - 统计信息
 */
@Data
@Builder
public class GraphResult {
    
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
     * 实体列表
     */
    @Builder.Default
    private List<GraphEntity> entities = Collections.emptyList();
    
    /**
     * 关系列表
     */
    @Builder.Default
    private List<GraphRelation> relations = Collections.emptyList();
    
    /**
     * Mermaid图表代码（可选）
     */
    private String mermaidDiagram;
    
    /**
     * 统计信息
     */
    private Map<String, Object> statistics;
    
    /**
     * 创建成功结果
     */
    public static GraphResult success(List<GraphEntity> entities, List<GraphRelation> relations) {
        return GraphResult.builder()
                .success(true)
                .entities(entities)
                .relations(relations)
                .build();
    }
    
    /**
     * 创建错误结果
     */
    public static GraphResult error(String message) {
        return GraphResult.builder()
                .success(false)
                .errorMessage(message)
                .build();
    }
    
    /**
     * 简化的图实体（对外暴露）
     */
    @Data
    @Builder
    public static class GraphEntity {
        /** 实体唯一ID */
        private String id;
        
        /** 实体名称（类名、方法名等） */
        private String name;
        
        /** 完全限定名 */
        private String qualifiedName;
        
        /** 实体类型：CLASS, INTERFACE, METHOD, FIELD, CONSTRUCTOR */
        private String type;
        
        /** 所在文件路径 */
        private String filePath;
        
        /** 起始行号 */
        private Integer startLine;
        
        /** 结束行号 */
        private Integer endLine;
        
        /** 扩展元数据 */
        private Map<String, Object> metadata;
    }
    
    /**
     * 简化的图关系（对外暴露）
     */
    @Data
    @Builder
    public static class GraphRelation {
        /** 源实体ID */
        private String sourceId;
        
        /** 目标实体ID */
        private String targetId;
        
        /** 关系类型：DEPENDS_ON, CALLS, EXTENDS, IMPLEMENTS, CONTAINS */
        private String type;
        
        /** 扩展元数据 */
        private Map<String, Object> metadata;
    }
}
