package io.leavesfly.jimi.core.hook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.common.ScriptRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Hook 执行器
 *
 * 职责:
 * - 执行 Hook 的脚本或命令
 * - 检查执行条件
 * - 处理异步执行
 *
 * 对齐 Claude Code 标准:
 * - 通过 stdin 传递 JSON 格式的上下文数据给 hook 脚本
 * - 通过 exit code 控制决策（0=允许, 2=阻塞, 其他=非阻塞错误）
 * - 解析 stdout JSON 输出获取精细控制信息
 * - 支持 composite 组合执行
 */
@Slf4j
@Service
public class HookExecutor {

    @Autowired
    private ScriptRunner scriptRunner;

    @Autowired
    private ObjectMapper objectMapper;

    /** Exit code 0: 成功，允许操作继续 */
    private static final int EXIT_CODE_SUCCESS = 0;

    /** Exit code 2: 阻塞错误，阻止操作（对齐 Claude Code） */
    private static final int EXIT_CODE_BLOCK = 2;

    /**
     * Hook 执行结果
     */
    @lombok.Value
    @lombok.Builder
    public static class HookResult {
        boolean success;
        boolean blocked;
        String reason;
        String stdout;
        String stderr;
        int exitCode;
        @lombok.Builder.Default
        Map<String, Object> jsonOutput = Map.of();

        public static HookResult success() {
            return HookResult.builder().success(true).exitCode(EXIT_CODE_SUCCESS).build();
        }

        public static HookResult blocked(String reason, String stderr) {
            return HookResult.builder()
                    .success(false).blocked(true)
                    .reason(reason).stderr(stderr)
                    .exitCode(EXIT_CODE_BLOCK).build();
        }

        public static HookResult error(String reason) {
            return HookResult.builder()
                    .success(false).reason(reason).exitCode(1).build();
        }
    }

    /**
     * 执行 Hook 并返回结果
     */
    public Mono<HookResult> executeWithResult(HookSpec hook, HookContext context) {
        log.debug("Executing hook: {} (type={})", hook.getName(), hook.getTrigger().getType());

        if (!checkConditions(hook.getConditions(), context)) {
            log.debug("Hook conditions not met: {}", hook.getName());
            return Mono.just(HookResult.success());
        }

        ExecutionSpec execution = hook.getExecution();

        Mono<HookResult> executionMono = switch (execution.getType()) {
            case "script" -> executeScriptWithResult(hook, context);
            case "agent" -> executeAgentWithResult(hook, context);
            case "composite" -> executeCompositeWithResult(hook, context);
            default -> {
                log.error("Unknown execution type: {}", execution.getType());
                yield Mono.just(HookResult.error("Unknown execution type: " + execution.getType()));
            }
        };

        if (execution.isAsync()) {
            executionMono.subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            result -> log.info("Async hook completed: {} (success={})",
                                    hook.getName(), result.isSuccess()),
                            error -> log.error("Async hook failed: {}", hook.getName(), error)
                    );
            return Mono.just(HookResult.success());
        }

        return executionMono.subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 执行 Hook（兼容旧接口，不返回结果）
     */
    public Mono<Void> execute(HookSpec hook, HookContext context) {
        return executeWithResult(hook, context).then();
    }

    /**
     * 检查执行条件（所有条件必须满足）
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
        String envValue = System.getenv(condition.getVar());
        if (envValue == null) {
            return false;
        }
        if (condition.getValue() != null) {
            return envValue.equals(condition.getValue());
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
            ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-c", script);
            if (context.getWorkDir() != null) {
                processBuilder.directory(context.getWorkDir().toFile());
            }

            Process process = processBuilder.start();
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == EXIT_CODE_SUCCESS;
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
     * 执行脚本类型 Hook，对齐 Claude Code 标准：
     * - 通过 stdin 传递 JSON 上下文
     * - 通过 exit code 控制决策
     * - 解析 stdout JSON 输出
     */
    private Mono<HookResult> executeScriptWithResult(HookSpec hook, HookContext context) {
        return Mono.fromCallable(() -> {
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

                ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-c", script);
                if (context.getWorkDir() != null) {
                    processBuilder.directory(context.getWorkDir().toFile());
                }
                processBuilder.environment().putAll(env);

                Process process = processBuilder.start();

                // 通过 stdin 传递 JSON 上下文（对齐 Claude Code）
                writeStdinJson(process, context);

                // 读取 stdout 和 stderr
                String stdout = readStream(process.getInputStream());
                String stderr = readStream(process.getErrorStream());

                int effectiveTimeout = execution.getTimeout() > 0 ? execution.getTimeout() : 60;
                boolean completed = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);

                if (!completed) {
                    process.destroyForcibly();
                    log.error("Hook script timed out after {}s: {}", effectiveTimeout, hook.getName());
                    return HookResult.error("Script timed out after " + effectiveTimeout + "s");
                }

                int exitCode = process.exitValue();
                Map<String, Object> jsonOutput = parseJsonOutput(stdout);

                if (exitCode == EXIT_CODE_SUCCESS) {
                    log.info("Hook executed successfully: {}", hook.getName());
                    return HookResult.builder()
                            .success(true).stdout(stdout).stderr(stderr)
                            .exitCode(exitCode).jsonOutput(jsonOutput).build();
                } else if (exitCode == EXIT_CODE_BLOCK) {
                    String blockReason = stderr != null && !stderr.isBlank() ? stderr.trim() : "Blocked by hook";
                    log.warn("Hook blocked action: {} - {}", hook.getName(), blockReason);
                    return HookResult.blocked(blockReason, stderr);
                } else {
                    log.warn("Hook non-blocking error (exit={}): {} - {}",
                            exitCode, hook.getName(), stderr);
                    return HookResult.builder()
                            .success(false).reason(stderr).stdout(stdout).stderr(stderr)
                            .exitCode(exitCode).jsonOutput(jsonOutput).build();
                }
            } catch (Exception e) {
                log.error("Failed to execute hook script: {}", hook.getName(), e);
                return HookResult.error("Script execution failed: " + e.getMessage());
            }
        });
    }

    /**
     * 执行 Agent 类型 Hook
     */
    private Mono<HookResult> executeAgentWithResult(HookSpec hook, HookContext context) {
        return Mono.fromCallable(() -> {
            ExecutionSpec execution = hook.getExecution();
            Map<String, String> variables = buildVariableMap(context);
            String resolvedTask = scriptRunner.replaceVariables(execution.getTask(), variables);

            log.info("Agent hook '{}' - agent: {}, task: {}", hook.getName(),
                    execution.getAgent(), resolvedTask);

            // Agent 执行需要与 Jimi 的 Agent 系统集成
            // 当前记录任务信息，后续可通过 AgentExecutor 接入
            log.warn("Agent execution delegates to agent '{}' with task: {}",
                    execution.getAgent(), resolvedTask);

            return HookResult.success();
        });
    }

    /**
     * 执行 Composite 类型 Hook（按顺序执行多个步骤）
     */
    private Mono<HookResult> executeCompositeWithResult(HookSpec hook, HookContext context) {
        ExecutionSpec execution = hook.getExecution();
        List<ExecutionSpec.ExecutionStep> steps = execution.getSteps();

        if (steps == null || steps.isEmpty()) {
            return Mono.just(HookResult.error("No steps defined for composite hook"));
        }

        return Flux.fromIterable(steps)
                .concatMap(step -> executeStep(step, hook.getName(), context))
                .takeUntil(result -> !result.isSuccess() && !isStepContinueOnFailure(steps, result))
                .last()
                .defaultIfEmpty(HookResult.success());
    }

    private Mono<HookResult> executeStep(ExecutionSpec.ExecutionStep step, String hookName,
                                         HookContext context) {
        return Mono.fromCallable(() -> {
            try {
                String stepDescription = step.getDescription() != null
                        ? step.getDescription() : "unnamed step";
                log.debug("Executing composite step '{}' for hook '{}'", stepDescription, hookName);

                Map<String, String> variables = buildVariableMap(context);
                String script = scriptRunner.replaceVariables(step.getScript(), variables);

                ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-c", script);
                if (context.getWorkDir() != null) {
                    processBuilder.directory(context.getWorkDir().toFile());
                }
                processBuilder.redirectErrorStream(true);

                Process process = processBuilder.start();
                String output = readStream(process.getInputStream());

                int timeout = step.getTimeout() > 0 ? step.getTimeout() : 60;
                boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);

                if (!completed) {
                    process.destroyForcibly();
                    String errorMessage = "Step '" + stepDescription + "' timed out";
                    log.error("{} in hook '{}'", errorMessage, hookName);
                    return HookResult.error(errorMessage);
                }

                int exitCode = process.exitValue();
                if (exitCode != EXIT_CODE_SUCCESS) {
                    String errorMessage = "Step '" + stepDescription + "' failed (exit=" + exitCode + ")";
                    log.warn("{} in hook '{}': {}", errorMessage, hookName, output);
                    if (!step.isContinueOnFailure()) {
                        return HookResult.error(errorMessage);
                    }
                }

                log.debug("Step '{}' completed successfully for hook '{}'", stepDescription, hookName);
                return HookResult.success();
            } catch (Exception e) {
                log.error("Failed to execute step in hook '{}'", hookName, e);
                return HookResult.error("Step execution failed: " + e.getMessage());
            }
        });
    }

    private boolean isStepContinueOnFailure(List<ExecutionSpec.ExecutionStep> steps,
                                            HookResult result) {
        // 如果当前步骤配置了 continueOnFailure，则继续执行
        return steps.stream().anyMatch(ExecutionSpec.ExecutionStep::isContinueOnFailure);
    }

    /**
     * 通过 stdin 向 hook 进程传递 JSON 上下文（对齐 Claude Code）
     */
    private void writeStdinJson(Process process, HookContext context) {
        try {
            Map<String, Object> stdinData = context.toStdinJson();
            String jsonString = objectMapper.writeValueAsString(stdinData);

            try (OutputStream outputStream = process.getOutputStream()) {
                outputStream.write(jsonString.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
        } catch (Exception e) {
            log.debug("Failed to write stdin JSON to hook process: {}", e.getMessage());
        }
    }

    /**
     * 解析 hook 脚本的 stdout JSON 输出
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonOutput(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return Map.of();
        }

        String trimmed = stdout.trim();
        if (!trimmed.startsWith("{")) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(trimmed, Map.class);
        } catch (JsonProcessingException e) {
            log.debug("Hook stdout is not valid JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private String readStream(java.io.InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.debug("Failed to read process stream: {}", e.getMessage());
            return "";
        }
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
     * 从 HookContext 构建变量映射（用于脚本中的 ${VAR} 替换）
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
        if (context.getAffectedFiles() != null && !context.getAffectedFiles().isEmpty()) {
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
        if (context.getUserInput() != null) {
            variables.put("USER_INPUT", context.getUserInput());
        }
        if (context.getNotificationMessage() != null) {
            variables.put("NOTIFICATION_MESSAGE", context.getNotificationMessage());
        }
        if (context.getLastAssistantMessage() != null) {
            variables.put("LAST_ASSISTANT_MESSAGE", context.getLastAssistantMessage());
        }
        return variables;
    }
}
