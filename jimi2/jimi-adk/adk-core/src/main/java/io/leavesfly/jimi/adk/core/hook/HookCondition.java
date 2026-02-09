package io.leavesfly.jimi.adk.core.hook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Hook 执行条件
 * 只有当所有条件都满足时,Hook 才会执行
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HookCondition {
    
    /** 条件类型: env_var, file_exists, script, tool_result_contains */
    private String type;
    
    /** 环境变量名称 (type=env_var) */
    private String var;
    
    /** 期望值 (type=env_var) */
    private String value;
    
    /** 文件路径 (type=file_exists) */
    private String path;
    
    /** 脚本内容 (type=script) */
    private String script;
    
    /** 匹配模式 (type=tool_result_contains, 正则) */
    private String pattern;
    
    /** 条件描述 */
    private String description;
    
    public void validate() {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Condition type is required");
        }
        switch (type) {
            case "env_var":
                if (var == null || var.trim().isEmpty()) {
                    throw new IllegalArgumentException("Variable name is required for env_var condition");
                }
                break;
            case "file_exists":
                if (path == null || path.trim().isEmpty()) {
                    throw new IllegalArgumentException("Path is required for file_exists condition");
                }
                break;
            case "script":
                if (script == null || script.trim().isEmpty()) {
                    throw new IllegalArgumentException("Script is required for script condition");
                }
                break;
            case "tool_result_contains":
                if (pattern == null || pattern.trim().isEmpty()) {
                    throw new IllegalArgumentException("Pattern is required for tool_result_contains condition");
                }
                break;
            default:
                throw new IllegalArgumentException(
                    "Invalid condition type: " + type + 
                    ". Supported: env_var, file_exists, script, tool_result_contains"
                );
        }
    }
}
