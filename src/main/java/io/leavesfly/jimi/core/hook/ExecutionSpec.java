package io.leavesfly.jimi.core.hook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 执行配置
 *
 * 定义 Hook 的执行方式，支持三种类型：
 * - script: 执行 Shell 脚本（内联或外部文件）
 * - agent: 委托给 Agent 执行
 * - composite: 组合多个执行步骤
 *
 * 对齐 Claude Code 标准，支持：
 * - command 类型（对应 script）
 * - prompt 类型（对应 agent）
 * - 异步执行（async）
 * - 超时控制（timeout）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionSpec {

    /**
     * 执行类型 (必需)
     * 支持: "script", "agent", "composite"
     */
    private String type;

    /**
     * 内联脚本内容 (type=script 时使用)
     */
    private String script;

    /**
     * 外部脚本文件路径 (type=script 时使用，与 script 二选一)
     */
    private String scriptFile;

    /**
     * 工作目录 (type=script 时可选)
     * 支持变量替换，如 ${JIMI_WORK_DIR}
     */
    private String workingDir;

    /**
     * 超时时间（秒）(可选，默认 60)
     */
    @Builder.Default
    private int timeout = 60;

    /**
     * 额外的环境变量 (type=script 时可选)
     */
    @Builder.Default
    private Map<String, String> environment = new HashMap<>();

    /**
     * Agent 名称 (type=agent 时必需)
     */
    private String agent;

    /**
     * Agent 任务描述 (type=agent 时必需)
     * 支持变量替换
     */
    private String task;

    /**
     * 组合执行步骤 (type=composite 时必需)
     */
    @Builder.Default
    private List<ExecutionStep> steps = new ArrayList<>();

    /**
     * 是否异步执行 (可选，默认 false)
     * 对齐 Claude Code 的 async 特性
     */
    @Builder.Default
    private boolean async = false;

    /**
     * 步骤描述 (用于 composite 中的子步骤)
     */
    private String description;

    /**
     * 失败时是否继续 (用于 composite 中的子步骤)
     */
    @Builder.Default
    private boolean continueOnFailure = false;

    /**
     * 验证配置有效性
     */
    public void validate() {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Execution type is required");
        }

        switch (type) {
            case "script":
                if ((script == null || script.trim().isEmpty())
                        && (scriptFile == null || scriptFile.trim().isEmpty())) {
                    throw new IllegalArgumentException(
                            "Either 'script' or 'scriptFile' is required for script execution");
                }
                break;

            case "agent":
                if (agent == null || agent.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Agent name is required for agent execution");
                }
                if (task == null || task.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Task description is required for agent execution");
                }
                break;

            case "composite":
                if (steps == null || steps.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Steps are required for composite execution");
                }
                break;

            default:
                throw new IllegalArgumentException(
                        "Invalid execution type: " + type
                                + ". Supported types: script, agent, composite");
        }
    }

    /**
     * 组合执行中的单个步骤
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecutionStep {

        /**
         * 步骤执行类型
         */
        private String type;

        /**
         * 脚本内容
         */
        private String script;

        /**
         * 步骤描述
         */
        private String description;

        /**
         * 失败时是否继续执行后续步骤
         */
        @Builder.Default
        private boolean continueOnFailure = false;

        /**
         * 超时时间（秒）
         */
        @Builder.Default
        private int timeout = 60;
    }
}
