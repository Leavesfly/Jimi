package io.leavesfly.jimi.command.custom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 组合命令中的单个步骤规范
 *
 * 用于 composite 类型执行配置中的步骤定义。
 *
 * 示例:
 * <pre>
 * steps:
 *   - type: "command"
 *     command: "/reset"
 *     description: "清除上下文"
 *     continueOnFailure: false
 *   - type: "script"
 *     script: "mvn clean install"
 *     description: "构建项目"
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompositeStepSpec {

    /**
     * 步骤类型 (必需)
     * 支持: "script", "command"
     */
    private String type;

    /**
     * 脚本内容 (type=script 时使用)
     */
    private String script;

    /**
     * 命令名称 (type=command 时使用，含 / 前缀)
     */
    private String command;

    /**
     * 步骤描述 (可选)
     */
    private String description;

    /**
     * 失败时是否继续执行后续步骤 (可选, 默认 false)
     */
    @Builder.Default
    private boolean continueOnFailure = false;

    /**
     * 超时时间（秒）(可选, 默认 60)
     */
    @Builder.Default
    private int timeout = 60;

    /**
     * 验证步骤配置有效性
     */
    public void validate() {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Step type is required");
        }

        switch (type) {
            case "script" -> {
                if (script == null || script.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Script is required for script step");
                }
            }
            case "command" -> {
                if (command == null || command.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Command is required for command step");
                }
            }
            default -> throw new IllegalArgumentException(
                    "Invalid step type: " + type
                            + ". Supported types: script, command");
        }
    }
}
