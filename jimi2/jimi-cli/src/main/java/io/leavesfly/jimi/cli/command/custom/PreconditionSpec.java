package io.leavesfly.jimi.cli.command.custom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 命令前置条件规范
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreconditionSpec {
    
    /** 条件类型: file_exists, dir_exists, env_var, command_exists */
    private String type;
    
    /** 文件/目录路径 */
    private String path;
    
    /** 环境变量名称 */
    private String var;
    
    /** 期望值 */
    private String value;
    
    /** 命令名称 */
    private String command;
    
    /** 错误消息 */
    private String errorMessage;
    
    public void validate() {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Precondition type is required");
        }
        switch (type) {
            case "file_exists":
            case "dir_exists":
                if (path == null || path.trim().isEmpty()) {
                    throw new IllegalArgumentException("Path is required for " + type + " precondition");
                }
                break;
            case "env_var":
                if (var == null || var.trim().isEmpty()) {
                    throw new IllegalArgumentException("Variable name is required for env_var precondition");
                }
                break;
            case "command_exists":
                if (command == null || command.trim().isEmpty()) {
                    throw new IllegalArgumentException("Command is required for command_exists precondition");
                }
                break;
            default:
                throw new IllegalArgumentException(
                    "Invalid precondition type: " + type + 
                    ". Supported: file_exists, dir_exists, env_var, command_exists"
                );
        }
    }
}
