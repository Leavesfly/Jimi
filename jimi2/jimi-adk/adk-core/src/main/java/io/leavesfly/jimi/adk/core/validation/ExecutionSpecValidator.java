package io.leavesfly.jimi.adk.core.validation;

import io.leavesfly.jimi.adk.api.model.CompositeStepSpec;
import io.leavesfly.jimi.adk.api.model.ExecutionSpec;

/**
 * ExecutionSpec 验证器
 * <p>
 * 将验证逻辑从数据模型中分离，保持 API 层的 DTO 纯净性。
 * </p>
 */
public final class ExecutionSpecValidator {

    private ExecutionSpecValidator() {
    }

    /**
     * 验证 ExecutionSpec 配置有效性
     *
     * @param spec 执行配置
     * @throws IllegalArgumentException 如果配置无效
     */
    public static void validate(ExecutionSpec spec) {
        if (spec.getType() == null || spec.getType().trim().isEmpty()) {
            throw new IllegalArgumentException("Execution type is required");
        }

        switch (spec.getType()) {
            case "script":
                validateScriptExecution(spec);
                break;
            case "agent":
                validateAgentExecution(spec);
                break;
            case "composite":
                validateCompositeExecution(spec);
                break;
            default:
                throw new IllegalArgumentException(
                    "Invalid execution type: " + spec.getType() +
                    ". Supported types: script, agent, composite"
                );
        }

        if (spec.getTimeout() <= 0) {
            throw new IllegalArgumentException("Timeout must be positive, got: " + spec.getTimeout());
        }
    }

    /**
     * 验证 CompositeStepSpec 配置有效性
     *
     * @param step 步骤配置
     * @throws IllegalArgumentException 如果配置无效
     */
    public static void validateStep(CompositeStepSpec step) {
        if (step.getType() == null || step.getType().trim().isEmpty()) {
            throw new IllegalArgumentException("Step type is required");
        }

        switch (step.getType()) {
            case "command":
                if (step.getCommand() == null || step.getCommand().trim().isEmpty()) {
                    throw new IllegalArgumentException("Command is required for command step");
                }
                break;
            case "script":
                if (step.getScript() == null || step.getScript().trim().isEmpty()) {
                    throw new IllegalArgumentException("Script is required for script step");
                }
                break;
            default:
                throw new IllegalArgumentException(
                    "Invalid step type: " + step.getType() + ". Supported types: command, script"
                );
        }
    }

    private static void validateScriptExecution(ExecutionSpec spec) {
        if ((spec.getScript() == null || spec.getScript().trim().isEmpty()) &&
            (spec.getScriptFile() == null || spec.getScriptFile().trim().isEmpty())) {
            throw new IllegalArgumentException(
                "Either 'script' or 'scriptFile' is required for script execution"
            );
        }
    }

    private static void validateAgentExecution(ExecutionSpec spec) {
        if (spec.getAgent() == null || spec.getAgent().trim().isEmpty()) {
            throw new IllegalArgumentException("Agent name is required for agent execution");
        }
        if (spec.getTask() == null || spec.getTask().trim().isEmpty()) {
            throw new IllegalArgumentException("Task description is required for agent execution");
        }
    }

    private static void validateCompositeExecution(ExecutionSpec spec) {
        if (spec.getSteps() == null || spec.getSteps().isEmpty()) {
            throw new IllegalArgumentException("Steps are required for composite execution");
        }
        spec.getSteps().forEach(ExecutionSpecValidator::validateStep);
    }
}
