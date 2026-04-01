package io.leavesfly.jimi.command.custom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 自定义命令参数规范
 *
 * 定义命令接受的参数，支持类型校验和默认值。
 * 参数名会自动转换为环境变量（大写，连字符转下划线）。
 *
 * 示例:
 * <pre>
 * parameters:
 *   - name: "skip-tests"
 *     type: "boolean"
 *     defaultValue: "false"
 *     required: false
 *     description: "是否跳过测试"
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParameterSpec {

    /**
     * 参数名称 (必需)
     */
    private String name;

    /**
     * 参数类型 (可选, 默认 "string")
     * 支持: string, boolean, integer, path
     */
    @Builder.Default
    private String type = "string";

    /**
     * 默认值 (可选)
     */
    private String defaultValue;

    /**
     * 是否必需 (可选, 默认 false)
     */
    @Builder.Default
    private boolean required = false;

    /**
     * 参数描述 (可选)
     */
    private String description;

    /**
     * 将参数名转换为环境变量名
     * 规则: 大写，连字符转下划线
     *
     * @return 环境变量名
     */
    public String toEnvironmentVariableName() {
        return name.toUpperCase().replace('-', '_');
    }

    /**
     * 验证参数配置有效性
     */
    public void validate() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Parameter name is required");
        }

        if (type != null && !type.isEmpty()) {
            switch (type) {
                case "string", "boolean", "integer", "path" -> { }
                default -> throw new IllegalArgumentException(
                        "Invalid parameter type: " + type
                                + ". Supported types: string, boolean, integer, path");
            }
        }
    }
}
