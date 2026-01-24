package io.leavesfly.jimi.tool.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
     * 许可证信息（可选）
     * 用于标识 Skill 的许可协议，兼容 Claude Code Skills
     */
    private String license;
    
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
     * 资源文件夹路径
     * 可选字段，指向Skill目录下的resources文件夹
     */
    private Path resourcesPath;
    
    /**
     * 脚本文件夹路径
     * 可选字段，指向Skill目录下的scripts文件夹，兼容Claude Code Skills
     */
    private Path scriptsPath;
    
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
    
    /**
     * 脚本文件路径（相对于Skill目录）
     * 可选字段，如果指定则在Skill激活时执行脚本
     * 例如："setup.sh", "scripts/init.py"
     */
    private String scriptPath;
    
    /**
     * 脚本类型
     * 可选值：bash, python, node, ruby 等
     * 如果未指定，则根据文件扩展名自动推断
     */
    private String scriptType;
    
    /**
     * 是否自动执行脚本
     * 默认为 true，设置为 false 则仅注入内容不执行脚本
     */
    @Builder.Default
    private boolean autoExecute = true;
    
    /**
     * 脚本执行的环境变量
     * 可选字段，为脚本执行提供额外的环境变量
     */
    private Map<String, String> scriptEnv;
    
    /**
     * 脚本执行超时时间（秒）
     * 默认为 60 秒，0 表示使用全局配置
     */
    @Builder.Default
    private int scriptTimeout = 0;
}
