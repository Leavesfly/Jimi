package io.leavesfly.jimi.command.custom;

import io.leavesfly.jimi.core.hook.ExecutionSpec;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义命令规范
 *
 * 用于从 YAML 配置文件加载用户自定义命令。
 * 配置文件位置: ~/.jimi/commands/*.yaml 或 project/.jimi/commands/*.yaml
 *
 * 支持四种执行类型:
 * - script: 执行 Shell 脚本
 * - agent: 委托给 Agent 执行
 * - composite: 组合多个步骤
 * - prompt: 作为 Prompt 发送给 AI（对齐 Claude Code 标准）
 *
 * 示例配置:
 * <pre>
 * name: "quick-build"
 * description: "快速构建并运行测试"
 * category: "build"
 * aliases:
 *   - "qb"
 * execution:
 *   type: "script"
 *   script: "mvn clean install"
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomCommandSpec {

    /**
     * 命令名称 (必需)
     */
    private String name;

    /**
     * 命令描述 (必需)
     */
    private String description;

    /**
     * 命令分类 (可选, 默认 "custom")
     */
    @Builder.Default
    private String category = "custom";

    /**
     * 命令优先级 (可选, 默认 0)
     */
    @Builder.Default
    private int priority = 0;

    /**
     * 命令别名列表 (可选)
     */
    @Builder.Default
    private List<String> aliases = new ArrayList<>();

    /**
     * 命令用法说明 (可选)
     */
    private String usage;

    /**
     * 参数定义列表 (可选)
     */
    @Builder.Default
    private List<ParameterSpec> parameters = new ArrayList<>();

    /**
     * 执行配置 (必需)
     * 复用 Hook 系统的 ExecutionSpec，支持 script/agent/composite 类型
     */
    private ExecutionSpec execution;

    /**
     * Prompt 模板内容 (type=prompt 时使用)
     * 对齐 Claude Code 标准，支持 $ARGUMENTS 占位符
     */
    private String prompt;

    /**
     * 前置条件列表 (可选)
     */
    @Builder.Default
    private List<PreconditionSpec> preconditions = new ArrayList<>();

    /**
     * 是否需要审批 (可选, 默认 false)
     */
    @Builder.Default
    private boolean requireApproval = false;

    /**
     * 是否启用 (可选, 默认 true)
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * 配置文件路径 (运行时设置)
     */
    private String configFilePath;

    /**
     * 验证配置有效性
     */
    public void validate() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Command name is required");
        }

        if (description == null || description.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Command description is required for: " + name);
        }

        boolean hasExecution = execution != null;
        boolean hasPrompt = prompt != null && !prompt.trim().isEmpty();

        if (!hasExecution && !hasPrompt) {
            throw new IllegalArgumentException(
                    "Either 'execution' or 'prompt' is required for command: " + name);
        }

        if (hasExecution) {
            execution.validate();
        }

        if (parameters != null) {
            parameters.forEach(ParameterSpec::validate);
        }

        if (preconditions != null) {
            preconditions.forEach(PreconditionSpec::validate);
        }
    }

    /**
     * 判断是否为 Prompt 类型命令（对齐 Claude Code 标准）
     *
     * @return 如果是 prompt 类型返回 true
     */
    public boolean isPromptType() {
        return prompt != null && !prompt.trim().isEmpty();
    }

    /**
     * 获取执行类型的显示名称
     *
     * @return 执行类型名称
     */
    public String getExecutionTypeName() {
        if (isPromptType()) {
            return "prompt";
        }
        return execution != null ? execution.getType() : "unknown";
    }
}
