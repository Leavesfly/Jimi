package io.leavesfly.jimi.tool.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Skill规格定义
 * 
 * 表示一个完整的Skill配置信息，包含元数据和内容。
 * 
 * 设计特性：
 * 1. **不可变配置对象**：创建后不应修改
 * 2. **缓存安全**：可在SkillRegistry中缓存
 * 3. **线程安全**：不包含可变状态
 * 
 * @see SkillScope Skill的作用域定义
 * @see SkillLoader Skill的加载器
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillSpec {
    
    /**
     * Skill名称（唯一标识）
     * 必需字段
     */
    private String name;
    
    /**
     * Skill简短描述（建议50字以内）
     * 必需字段
     */
    private String description;
    
    /**
     * 版本号（语义化版本，如1.0.0）
     * 默认值：1.0.0
     */
    @Builder.Default
    private String version = "1.0.0";
    
    /**
     * 分类标签（如development、documentation、testing）
     * 可选字段，用于分类查询
     */
    private String category;
    

    
    /**
     * 触发关键词列表
     * 用于智能匹配和激活Skill
     */
    @Builder.Default
    private List<String> triggers = new ArrayList<>();
    
    /**
     * 依赖的其他 Skill 名称列表
     * 用于组合/聚合型 Skill，通过名称引用其他 Skill
     * 可选字段，未配置时视为无依赖
     */
    @Builder.Default
    private List<String> dependencies = new ArrayList<>();
    
    /**
     * Skill指令内容（Markdown正文）
     * 必需字段，当Skill激活时注入到Agent上下文
     */
    private String content;
    

    

    
    /**
     * 作用域（全局或项目级）
     * 必需字段
     */
    private SkillScope scope;
    
    /**
     * Skill文件所在路径（用于调试和日志）
     * 可选字段
     */
    private Path skillFilePath;
    

}
