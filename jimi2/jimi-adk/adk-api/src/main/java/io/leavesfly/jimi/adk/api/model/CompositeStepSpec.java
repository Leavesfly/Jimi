package io.leavesfly.jimi.adk.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 组合执行步骤规范
 * 
 * 定义组合命令中的单个执行步骤
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompositeStepSpec {
    
    /**
     * 步骤类型 (必需)
     * 支持: command, script
     */
    private String type;
    
    /**
     * 命令名称 (type=command 时必需)
     */
    private String command;
    
    /**
     * 脚本内容 (type=script 时必需)
     */
    private String script;
    
    /**
     * 步骤描述 (可选)
     */
    private String description;
    
    /**
     * 失败时是否继续 (可选, 默认 false)
     */
    @Builder.Default
    private boolean continueOnFailure = false;
    
    /**
     * 验证配置有效性
     */
    public void validate() {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Step type is required");
        }
        
        switch (type) {
            case "command":
                if (command == null || command.trim().isEmpty()) {
                    throw new IllegalArgumentException("Command is required for command step");
                }
                break;
            case "script":
                if (script == null || script.trim().isEmpty()) {
                    throw new IllegalArgumentException("Script is required for script step");
                }
                break;
            default:
                throw new IllegalArgumentException(
                    "Invalid step type: " + type + ". Supported types: command, script"
                );
        }
    }
}
