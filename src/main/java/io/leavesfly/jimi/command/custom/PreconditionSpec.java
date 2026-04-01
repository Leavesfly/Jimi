package io.leavesfly.jimi.command.custom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 自定义命令前置条件规范
 *
 * 在命令执行前检查环境是否满足要求。
 *
 * 支持的条件类型:
 * - file_exists: 检查文件是否存在
 * - dir_exists: 检查目录是否存在
 * - env_var: 检查环境变量是否设置
 * - command_exists: 检查命令是否可用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreconditionSpec {

    /**
     * 条件类型 (必需)
     * 支持: file_exists, dir_exists, env_var, command_exists
     */
    private String type;

    /**
     * 文件/目录路径 (type=file_exists/dir_exists 时使用)
     */
    private String path;

    /**
     * 环境变量名 (type=env_var 时使用)
     */
    private String var;

    /**
     * 环境变量期望值 (type=env_var 时可选)
     */
    private String value;

    /**
     * 命令名称 (type=command_exists 时使用)
     */
    private String command;

    /**
     * 条件不满足时的错误消息
     */
    private String errorMessage;

    /**
     * 验证条件配置有效性
     */
    public void validate() {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Precondition type is required");
        }

        switch (type) {
            case "file_exists", "dir_exists" -> {
                if (path == null || path.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Path is required for " + type + " precondition");
                }
            }
            case "env_var" -> {
                if (var == null || var.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Variable name is required for env_var precondition");
                }
            }
            case "command_exists" -> {
                if (command == null || command.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Command name is required for command_exists precondition");
                }
            }
            default -> throw new IllegalArgumentException(
                    "Invalid precondition type: " + type
                            + ". Supported types: file_exists, dir_exists, env_var, command_exists");
        }
    }
}
