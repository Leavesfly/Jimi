package io.leavesfly.jimi.cli.command.custom;

import io.leavesfly.jimi.adk.api.model.ExecutionSpec;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义命令规范
 * 配置文件位置: ~/.jimi/commands/*.yaml 或 project/.jimi/commands/*.yaml
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomCommandSpec {
    
    /** 命令名称 (必需) */
    private String name;
    
    /** 命令描述 (必需) */
    private String description;
    
    /** 命令分类 (默认 "custom") */
    @Builder.Default
    private String category = "custom";
    
    /** 命令优先级 (默认 0) */
    @Builder.Default
    private int priority = 0;
    
    /** 命令别名列表 */
    @Builder.Default
    private List<String> aliases = new ArrayList<>();
    
    /** 命令用法说明 */
    private String usage;
    
    /** 参数定义列表 */
    @Builder.Default
    private List<ParameterSpec> parameters = new ArrayList<>();
    
    /** 执行配置 (必需) */
    private ExecutionSpec execution;
    
    /** 前置条件列表 */
    @Builder.Default
    private List<PreconditionSpec> preconditions = new ArrayList<>();
    
    /** 是否需要审批 (默认 false) */
    @Builder.Default
    private boolean requireApproval = false;
    
    /** 是否启用 (默认 true) */
    @Builder.Default
    private boolean enabled = true;
    
    /** 配置文件路径 (运行时设置) */
    private String configFilePath;
    
    public void validate() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Command name is required");
        }
        if (description == null || description.trim().isEmpty()) {
            throw new IllegalArgumentException("Command description is required for: " + name);
        }
        if (execution == null) {
            throw new IllegalArgumentException("Execution config is required for: " + name);
        }
        execution.validate();
        if (parameters != null) {
            parameters.forEach(ParameterSpec::validate);
        }
        if (preconditions != null) {
            preconditions.forEach(PreconditionSpec::validate);
        }
    }
}
