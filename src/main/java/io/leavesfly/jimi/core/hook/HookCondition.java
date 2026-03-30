package io.leavesfly.jimi.core.hook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Hook 执行条件
 * 
 * 定义 Hook 执行的额外条件
 * 只有当所有条件都满足时,Hook 才会执行
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HookCondition {
    
    /**
     * 条件类型 (必需)
     * 支持: env_var, file_exists, script, tool_result_contains
     */
    private String type;
    
    /**
     * 环境变量名称 (type=env_var 时必需)
     */
    private String var;
    
    /**
     * 期望值 (type=env_var 时可选)
     */
    private String value;
    
    /**
     * 文件路径 (type=file_exists 时必需)
     */
    private String path;
    
    /**
     * 脚本内容 (type=script 时必需)
     * 脚本退出码为 0 表示条件满足
     */
    private String script;
    
    /**
     * 匹配模式 (type=tool_result_contains 时必需)
     * 支持正则表达式
     */
    private String pattern;
    
    /**
     * 条件描述 (可选)
     */
    private String description;
    
    /**
     * 验证配置有效性
     */
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
                    ". Supported types: env_var, file_exists, script, tool_result_contains"
                );
        }
    }
}
