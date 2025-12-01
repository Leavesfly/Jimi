package io.leavesfly.jimi.graph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 代码实体
 * <p>
 * 表示代码库中的一个实体 (包、文件、类、方法、字段等)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeEntity {
    
    /**
     * 实体唯一标识 (生成规则: type:qualifiedName)
     */
    private String id;
    
    /**
     * 实体类型
     */
    private EntityType type;
    
    /**
     * 实体名称 (简单名称)
     */
    private String name;
    
    /**
     * 全限定名 (如: io.leavesfly.jimi.JimiEngine.start)
     */
    private String qualifiedName;
    
    /**
     * 所在文件路径 (相对于项目根目录)
     */
    private String filePath;
    
    /**
     * 起始行号 (从1开始)
     */
    private Integer startLine;
    
    /**
     * 结束行号
     */
    private Integer endLine;
    
    /**
     * 可见性 (public/private/protected/package-private)
     */
    private String visibility;
    
    /**
     * 是否为静态
     */
    @Builder.Default
    private Boolean isStatic = false;
    
    /**
     * 是否为抽象
     */
    @Builder.Default
    private Boolean isAbstract = false;
    
    /**
     * 扩展属性 (存储额外信息)
     */
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();
    
    /**
     * 创建时间戳
     */
    @Builder.Default
    private Long createdAt = System.currentTimeMillis();
    
    /**
     * 生成实体ID
     */
    public static String generateId(EntityType type, String qualifiedName) {
        return type.name() + ":" + qualifiedName;
    }
    
    /**
     * 获取简短描述
     */
    public String getDescription() {
        StringBuilder desc = new StringBuilder();
        desc.append(type.name().toLowerCase()).append(" ");
        desc.append(qualifiedName);
        if (filePath != null) {
            desc.append(" @ ").append(filePath);
            if (startLine != null) {
                desc.append(":").append(startLine);
            }
        }
        return desc.toString();
    }
    
    /**
     * 添加属性
     */
    public void addAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        attributes.put(key, value);
    }
    
    /**
     * 获取属性
     */
    public Object getAttribute(String key) {
        return attributes != null ? attributes.get(key) : null;
    }
}
