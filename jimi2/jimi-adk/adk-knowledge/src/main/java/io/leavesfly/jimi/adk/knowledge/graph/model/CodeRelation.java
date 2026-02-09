package io.leavesfly.jimi.adk.knowledge.graph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 代码关系
 * <p>
 * 表示两个代码实体之间的关系 (如: 继承、调用、引用等)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeRelation {
    
    /** 关系唯一标识 */
    @Builder.Default
    private String id = UUID.randomUUID().toString();
    /** 源实体ID */
    private String sourceId;
    /** 目标实体ID */
    private String targetId;
    /** 关系类型 */
    private RelationType type;
    /** 关系权重 (可用于排序或过滤, 默认1.0) */
    @Builder.Default
    private Double weight = 1.0;
    /** 扩展属性 (存储额外信息) */
    @Builder.Default
    private Map<String, Object> properties = new HashMap<>();
    /** 创建时间戳 */
    @Builder.Default
    private Long createdAt = System.currentTimeMillis();
    
    /** 获取关系描述 */
    public String getDescription() {
        return String.format("%s -[%s]-> %s", sourceId, type.name().toLowerCase(), targetId);
    }
    
    /** 添加属性 */
    public void addProperty(String key, Object value) {
        if (properties == null) {
            properties = new HashMap<>();
        }
        properties.put(key, value);
    }
    
    /** 获取属性 */
    public Object getProperty(String key) {
        return properties != null ? properties.get(key) : null;
    }
    
    /** 创建反向关系 */
    public CodeRelation reverse() {
        return CodeRelation.builder()
            .sourceId(targetId)
            .targetId(sourceId)
            .type(type)
            .weight(weight)
            .properties(new HashMap<>(properties))
            .build();
    }
}
