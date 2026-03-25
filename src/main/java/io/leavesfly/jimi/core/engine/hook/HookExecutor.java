package io.leavesfly.jimi.core.engine.hook;

import io.leavesfly.jimi.command.custom.ExecutionSpec;
import io.leavesfly.jimi.common.ScriptRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Hook 执行器
 *
 * 职责:
 * - 执行 Hook 的脚本或命令
 * - 检查执行条件
 * - 处理异步执行
 */
@Slf4j
@Service
public class HookExecutor {

    @Autowired
    private ScriptRunner scriptRunner;

    /**
     * 执行 Hook
     */
    public Mono<Void> execute(HookSpec hook, HookContext context) {
        log.debug("Executing hook: {} (type={})", hook.getName(), hook.getTrigger().getType());

        if (!checkConditions(hook.getConditions(), context)) {
            log.debug("Hook conditions not met: {}", hook.getName());
            return Mono.empty();
        }

        ExecutionSpec execution = hook.getExecution();

        Mono<Void> executionMono = switch (execution.getType()) {
            case "script" -> executeScript(hook, context);
            case "agent" -> executeAgent(hook, context);
            case "composite" -> executeComposite(hook, context);
            default -> {
                log.error("Unknown execution type: {}", execution.getType());
                yield Mono.empty();
            }
        };

        return executionMono.subscribeOn(Schedulers.parallel());
    }

    /**
     * 检查执行条件
     */
    private boolean checkConditions(List<HookCondition> conditions, HookContext context) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (HookCondition condition : conditions) {
            if (!checkCondition(condition, context)) {
                log.debug("Condition not met: {}", condition.getDescription());
                return false;
            }
        }
        return true;
    }

    private boolean checkCondition(HookCondition condition, HookContext context) {
        return switch (condition.getType()) {
            case "env_var" -> checkEnvVar(condition);
            case "file_exists" -> checkFileExists(condition, context);
            case "script" -> checkScript(condition, context);
            case "tool_result_contains" -> checkToolResultContains(condition, context);
            default -> {
                log.warn("Unknown condition type: {}", condition.getType());
                yield false;
            }
        };
    }

    private boolean checkEnvVar(HookCondition condition) {
        String value = System.getenv(condition.getVar());
        if (value == null) {
            return false;
        }
        if (condition.getValue() != null) {
            return value.equals(condition.getValue());
        }
        return true;
    }

    private boolean checkFileExists(HookCondition condition, HookContext context) {
        Map<String, String> variables = buildVariableMap(context);
        String resolved = scriptRunner.replaceVariables(condition.getPath(), variables);
        Path path = Path.of(resolved);
        if (!path.isAbsolute() && context.getWorkDir() != null) {
            path = context.getWorkDir().resolve(path);
        }
        return Files.exists(path);
    }

    private boolean checkScript(HookCondition condition, HookContext context) {
        try {
            Map<String, String> variables = buildVariableMap(context);
            String script = scriptRunner.replaceVariables(condition.getScript(), variables);
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", script);
            pb.directory(context.getWorkDir().toFile());

            Process process = pb.start();
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.error("Failed to check script condition", e);
            return false;
        }
    }

    private boolean checkToolResultContains(HookCondition condition, HookContext context) {
        if (context.getToolResult() == null) {
            return false;
        }
        return context.getToolResult().matches(condition.getPattern());
    }

    /**
     * 执行脚本类型 Hook（委托给 ScriptRunner）
     */
    private Mono<Void> executeScript(HookSpec hook, HookContext context) {
        return Mono.fromRunnable(() -> {
            try {
                ExecutionSpec execution = hook.getExecution();
                String script = getScriptContent(execution);

                Map<String, String> variables = buildVariableMap(context);
                script = scriptRunner.replaceVariables(script, variables);

                Map<String, String> env = new HashMap<>();
                if (execution.getEnvironment() != null) {
                    execution.getEnvironment().forEach((key, value) ->
                            env.put(key, scriptRunner.replaceVariables(value, variables)));
                }
                if (context.getToolName() != null) {
                    env.put("HOOK_TOOL_NAME", context.getToolName());
                }
                if (context.getAgentName() != null) {
                    env.put("HOOK_AGENT_NAME", context.getAgentName());
                }

                scriptRunner.runInlineScript(script, context.getWorkDir(), execution.getTimeout(), env)
                        .block();

                log.info("Hook executed successfully: {}", hook.getName());
            } catch (Exception e) {
                log.error("Failed to execute hook script: {}", hook.getName(), e);
            }
        });
    }

    private Mono<Void> executeAgent(HookSpec hook, HookContext context) {
        return Mono.fromRunnable(() ->
                log.warn("Agent execution not yet implemented for hook: {}", hook.getName()));
    }

    private Mono<Void> executeComposite(HookSpec hook, HookContext context) {
        return Mono.fromRunnable(() ->
                log.warn("Composite execution not yet implemented for hook: {}", hook.getName()));
    }

    private String getScriptContent(ExecutionSpec execution) throws Exception {
        if (execution.getScriptFile() != null && !execution.getScriptFile().trim().isEmpty()) {
            Path scriptPath = Path.of(execution.getScriptFile());
            if (!Files.exists(scriptPath)) {
                throw new IllegalStateException("Script file not found: " + scriptPath);
            }
            return Files.readString(scriptPath);
        }
        return execution.getScript();
    }

    /**
     * 从 HookContext 构建变量映射
     */
    private Map<String, String> buildVariableMap(HookContext context) {
        Map<String, String> variables = new HashMap<>();
        if (context.getWorkDir() != null) {
            variables.put("JIMI_WORK_DIR", context.getWorkDir().toString());
        }
        variables.put("HOME", System.getProperty("user.home"));
        if (context.getToolName() != null) {
            variables.put("TOOL_NAME", context.getToolName());
        }
        if (context.getToolResult() != null) {
            variables.put("TOOL_RESULT", context.getToolResult());
        }
        if (!context.getAffectedFiles().isEmpty()) {
            variables.put("MODIFIED_FILES", String.join(" ", context.getAffectedFilePaths()));
            variables.put("MODIFIED_FILE", context.getAffectedFiles().get(0).toString());
        }
        if (context.getAgentName() != null) {
            variables.put("AGENT_NAME", context.getAgentName());
            variables.put("CURRENT_AGENT", context.getAgentName());
        }
        if (context.getPreviousAgentName() != null) {
            variables.put("PREVIOUS_AGENT", context.getPreviousAgentName());
        }
        if (context.getErrorMessage() != null) {
            variables.put("ERROR_MESSAGE", context.getErrorMessage());
        }
        return variables;
    }
}
