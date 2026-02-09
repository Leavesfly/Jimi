package io.leavesfly.jimi.adk.core.hook;

import io.leavesfly.jimi.adk.api.model.ExecutionSpec;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Hook 执行器
 * 负责执行 Hook 的脚本或命令，检查执行条件，处理异步执行
 */
@Slf4j
public class HookExecutor {
    
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
        if (value == null) return false;
        if (condition.getValue() != null) return value.equals(condition.getValue());
        return true;
    }
    
    private boolean checkFileExists(HookCondition condition, HookContext context) {
        Path path = resolvePath(condition.getPath(), context);
        return Files.exists(path);
    }
    
    private boolean checkScript(HookCondition condition, HookContext context) {
        try {
            String script = replaceVariables(condition.getScript(), context);
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", script);
            if (context.getWorkDir() != null) {
                pb.directory(context.getWorkDir().toFile());
            }
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
        if (context.getToolResult() == null) return false;
        return context.getToolResult().matches(condition.getPattern());
    }
    
    private Mono<Void> executeScript(HookSpec hook, HookContext context) {
        return Mono.fromRunnable(() -> {
            try {
                ExecutionSpec execution = hook.getExecution();
                String script = getScriptContent(execution);
                script = replaceVariables(script, context);
                Map<String, String> env = buildEnvironment(execution, context);
                
                ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", script);
                if (context.getWorkDir() != null) {
                    pb.directory(context.getWorkDir().toFile());
                }
                pb.redirectErrorStream(true);
                if (env != null && !env.isEmpty()) {
                    pb.environment().putAll(env);
                }
                
                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[Hook:{}] {}", hook.getName(), line);
                    }
                }
                
                int timeout = execution.getTimeout();
                boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    log.error("Hook script timeout: {}", hook.getName());
                    return;
                }
                
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    log.error("Hook script failed with exit code {}: {}", exitCode, hook.getName());
                } else {
                    log.info("Hook executed successfully: {}", hook.getName());
                }
            } catch (Exception e) {
                log.error("Failed to execute hook script: {}", hook.getName(), e);
            }
        });
    }
    
    private Mono<Void> executeAgent(HookSpec hook, HookContext context) {
        return Mono.fromRunnable(() -> {
            log.warn("Agent execution not yet implemented for hook: {}", hook.getName());
        });
    }
    
    private Mono<Void> executeComposite(HookSpec hook, HookContext context) {
        return Mono.fromRunnable(() -> {
            log.warn("Composite execution not yet implemented for hook: {}", hook.getName());
        });
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
    
    private String replaceVariables(String text, HookContext context) {
        if (text == null) return null;
        String result = text;
        if (context.getWorkDir() != null) {
            result = result.replace("${JIMI_WORK_DIR}", context.getWorkDir().toString());
        }
        result = result.replace("${HOME}", System.getProperty("user.home"));
        if (context.getToolName() != null) {
            result = result.replace("${TOOL_NAME}", context.getToolName());
        }
        if (context.getToolResult() != null) {
            result = result.replace("${TOOL_RESULT}", context.getToolResult());
        }
        if (!context.getAffectedFiles().isEmpty()) {
            String files = String.join(" ", context.getAffectedFilePaths());
            result = result.replace("${MODIFIED_FILES}", files);
            result = result.replace("${MODIFIED_FILE}", context.getAffectedFiles().get(0).toString());
        }
        if (context.getAgentName() != null) {
            result = result.replace("${AGENT_NAME}", context.getAgentName());
            result = result.replace("${CURRENT_AGENT}", context.getAgentName());
        }
        if (context.getPreviousAgentName() != null) {
            result = result.replace("${PREVIOUS_AGENT}", context.getPreviousAgentName());
        }
        if (context.getErrorMessage() != null) {
            result = result.replace("${ERROR_MESSAGE}", context.getErrorMessage());
        }
        return result;
    }
    
    private Map<String, String> buildEnvironment(ExecutionSpec execution, HookContext context) {
        Map<String, String> env = new HashMap<>();
        if (execution.getEnvironment() != null) {
            execution.getEnvironment().forEach((key, value) -> env.put(key, replaceVariables(value, context)));
        }
        if (context.getToolName() != null) env.put("HOOK_TOOL_NAME", context.getToolName());
        if (context.getAgentName() != null) env.put("HOOK_AGENT_NAME", context.getAgentName());
        return env;
    }
    
    private Path resolvePath(String pathStr, HookContext context) {
        String resolved = replaceVariables(pathStr, context);
        Path path = Path.of(resolved);
        if (!path.isAbsolute() && context.getWorkDir() != null) {
            path = context.getWorkDir().resolve(path);
        }
        return path;
    }
}
