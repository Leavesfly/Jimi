package io.leavesfly.jimi.adk.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skill 规格定义
 * 包含 Skill 的元数据和指令内容
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillSpec {
    
    /** Skill 名称（唯一标识） */
    private String name;
    
    /** 简短描述 */
    private String description;
    
    /** 版本号 */
    @Builder.Default
    private String version = "1.0.0";
    
    /** 分类标签 */
    private String category;
    
    /** 许可证信息 */
    private String license;
    
    /** 触发关键词列表 */
    @Builder.Default
    private List<String> triggers = new ArrayList<>();
    
    /** 依赖的其他 Skill 名称列表 */
    @Builder.Default
    private List<String> dependencies = new ArrayList<>();
    
    /** Skill 指令内容（Markdown 正文） */
    private String content;
    
    /** 资源文件夹路径 */
    private Path resourcesPath;
    
    /** 脚本文件夹路径 */
    private Path scriptsPath;
    
    /** 作用域 */
    private SkillScope scope;
    
    /** Skill 文件所在路径 */
    private Path skillFilePath;
    
    /** 脚本文件路径 */
    private String scriptPath;
    
    /** 脚本类型: bash, python, node, ruby */
    private String scriptType;
    
    /** 是否自动执行脚本 */
    @Builder.Default
    private boolean autoExecute = true;
    
    /** 脚本执行的环境变量 */
    private Map<String, String> scriptEnv;
    
    /** 脚本执行超时时间（秒, 0=使用全局配置） */
    @Builder.Default
    private int scriptTimeout = 0;
}
