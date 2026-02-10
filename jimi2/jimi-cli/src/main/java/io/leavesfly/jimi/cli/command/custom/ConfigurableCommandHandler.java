package io.leavesfly.jimi.cli.command.custom;

import io.leavesfly.jimi.adk.api.model.ExecutionSpec;
import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 将 CustomCommandSpec 适配为 Command 接口
 * 支持执行脚本、Agent 委托和组合命令
 */
@Slf4j
public class ConfigurableCommandHandler implements Command {
    
    @Getter
    private final CustomCommandSpec spec;
    
    public ConfigurableCommandHandler(CustomCommandSpec spec) {
        this.spec = spec;
    }
    
    @Override
    public String getName() {
        return spec.getName();
    }
    
    @Override
    public String getDescription() {
        return spec.getDescription();
    }
    
    @Override
    public List<String> getAliases() {
        return spec.getAliases() != null ? spec.getAliases() : List.of();
    }
    
    @Override
    public String getUsage() {
        return spec.getUsage() != null ? spec.getUsage() : "/" + spec.getName();
    }
    
    @Override
    public int getPriority() {
        return spec.getPriority();
    }
    
    @Override
    public String getCategory() {
        return spec.getCategory() != null ? spec.getCategory() : "custom";
    }
    
    @Override
    public void execute(CommandContext context) throws Exception {
        // 检查前置条件
        if (!checkPreconditions(context)) {
            context.getOutput().println("Command preconditions not met");
            return;
        }
        
        ExecutionSpec execution = spec.getExecution();
        
        switch (execution.getType()) {
            case "script" -> executeScript(execution, context);
            case "agent" -> executeAgent(execution, context);
            case "composite" -> executeComposite(execution, context);
            default -> context.getOutput().println("Unknown execution type: " + execution.getType());
        }
    }
    
    @Override
    public boolean isAvailable(CommandContext context) {
        return spec.isEnabled();
    }
    
    private boolean checkPreconditions(CommandContext context) {
        if (spec.getPreconditions() == null || spec.getPreconditions().isEmpty()) {
            return true;
        }
        
        for (PreconditionSpec precondition : spec.getPreconditions()) {
            if (!checkPrecondition(precondition, context)) {
                String errorMsg = precondition.getErrorMessage() != null 
                        ? precondition.getErrorMessage()
                        : "Precondition failed: " + precondition.getType();
                context.getOutput().println(errorMsg);
                return false;
            }
        }
        return true;
    }
    
    private boolean checkPrecondition(PreconditionSpec precondition, CommandContext context) {
        return switch (precondition.getType()) {
            case "file_exists" -> {
                Path path = resolvePath(precondition.getPath(), context);
                yield Files.exists(path) && Files.isRegularFile(path);
            }
            case "dir_exists" -> {
                Path path = resolvePath(precondition.getPath(), context);
                yield Files.exists(path) && Files.isDirectory(path);
            }
            case "env_var" -> {
                String value = System.getenv(precondition.getVar());
                if (value == null) yield false;
                if (precondition.getValue() != null) yield value.equals(precondition.getValue());
                yield true;
            }
            case "command_exists" -> {
                try {
                    ProcessBuilder pb = new ProcessBuilder("which", precondition.getCommand());
                    Process process = pb.start();
                    boolean done = process.waitFor(5, TimeUnit.SECONDS);
                    yield done && process.exitValue() == 0;
                } catch (Exception e) {
                    yield false;
                }
            }
            default -> false;
        };
    }
    
    private void executeScript(ExecutionSpec execution, CommandContext context) throws Exception {
        String script = getScriptContent(execution);
        script = replaceVariables(script, context);
        
        Map<String, String> env = new HashMap<>();
        if (execution.getEnvironment() != null) {
            execution.getEnvironment().forEach((k, v) -> env.put(k, replaceVariables(v, context)));
        }
        // 添加参数到环境变量
        if (context.getArgs() != null) {
            for (int i = 0; i < context.getArgs().length; i++) {
                env.put("ARG_" + i, context.getArgs()[i]);
            }
            env.put("ARGS", context.getArgsAsString());
        }
        
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", script);
        if (context.getRuntime() != null && context.getRuntime().getConfig().getWorkDir() != null) {
            pb.directory(context.getRuntime().getConfig().getWorkDir().toFile());
        }
        pb.redirectErrorStream(true);
        if (!env.isEmpty()) {
            pb.environment().putAll(env);
        }
        
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                context.getOutput().println(line);
            }
        }
        
        boolean completed = process.waitFor(execution.getTimeout(), TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            context.getOutput().println("Command timed out after " + execution.getTimeout() + "s");
        } else if (process.exitValue() != 0) {
            context.getOutput().println("Command failed with exit code: " + process.exitValue());
        }
    }
    
    private void executeAgent(ExecutionSpec execution, CommandContext context) {
        context.getOutput().println("Agent delegation: " + execution.getAgent());
        context.getOutput().println("Task: " + execution.getTask());
        // Agent 委托需要由上层引擎协调，这里只输出信息
        log.warn("Agent execution for custom commands requires engine integration");
    }
    
    private void executeComposite(ExecutionSpec execution, CommandContext context) throws Exception {
        var steps = execution.getSteps();
        if (steps == null || steps.isEmpty()) {
            context.getOutput().println("No steps defined for composite command");
            return;
        }
        
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            context.getOutput().println("Step " + (i + 1) + "/" + steps.size() + 
                    (step.getDescription() != null ? ": " + step.getDescription() : ""));
            
            try {
                if ("script".equals(step.getType())) {
                    ExecutionSpec stepExecution = ExecutionSpec.builder()
                            .type("script")
                            .script(step.getScript())
                            .timeout(execution.getTimeout())
                            .build();
                    executeScript(stepExecution, context);
                } else if ("command".equals(step.getType())) {
                    context.getOutput().println("Execute command: " + step.getCommand());
                }
            } catch (Exception e) {
                if (step.isContinueOnFailure()) {
                    context.getOutput().println("Step failed (continuing): " + e.getMessage());
                } else {
                    throw e;
                }
            }
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
    
    private String replaceVariables(String text, CommandContext context) {
        if (text == null) return null;
        String result = text;
        result = result.replace("${HOME}", System.getProperty("user.home"));
        if (context.getRuntime() != null && context.getRuntime().getConfig().getWorkDir() != null) {
            result = result.replace("${JIMI_WORK_DIR}", context.getRuntime().getConfig().getWorkDir().toString());
            result = result.replace("${PROJECT_ROOT}", context.getRuntime().getConfig().getWorkDir().toString());
        }
        if (context.getArgsAsString() != null) {
            result = result.replace("${ARGS}", context.getArgsAsString());
        }
        return result;
    }
    
    private Path resolvePath(String pathStr, CommandContext context) {
        String resolved = replaceVariables(pathStr, context);
        Path path = Path.of(resolved);
        if (!path.isAbsolute() && context.getRuntime() != null && context.getRuntime().getConfig().getWorkDir() != null) {
            path = context.getRuntime().getConfig().getWorkDir().resolve(path);
        }
        return path;
    }
}
