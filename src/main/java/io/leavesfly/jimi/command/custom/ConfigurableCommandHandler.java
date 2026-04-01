package io.leavesfly.jimi.command.custom;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.core.hook.ExecutionSpec;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 可配置命令处理器
 *
 * 将 CustomCommandSpec 适配为 CommandHandler 接口，
 * 支持 script、agent、composite、prompt 四种执行类型。
 */
@Slf4j
public class ConfigurableCommandHandler implements CommandHandler {

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
        return spec.getAliases();
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
        return spec.getCategory();
    }

    @Override
    public void execute(CommandContext context) throws Exception {
        OutputFormatter out = context.getOutputFormatter();

        if (!checkPreconditions(context)) {
            return;
        }

        Map<String, String> parameterValues = resolveParameters(context);

        if (spec.isPromptType()) {
            executePrompt(context, parameterValues);
        } else {
            ExecutionSpec execution = spec.getExecution();
            switch (execution.getType()) {
                case "script" -> executeScript(execution, context, parameterValues);
                case "agent" -> executeAgent(execution, context, parameterValues);
                case "composite" -> executeComposite(execution, context, parameterValues);
                default -> out.printError("Unknown execution type: " + execution.getType());
            }
        }
    }

    /**
     * 检查前置条件
     */
    private boolean checkPreconditions(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        Path workDir = context.getEngineClient().getWorkDir();

        for (PreconditionSpec precondition : spec.getPreconditions()) {
            if (!evaluatePrecondition(precondition, workDir)) {
                String errorMessage = precondition.getErrorMessage() != null
                        ? precondition.getErrorMessage()
                        : "Precondition failed: " + precondition.getType();
                out.printError(errorMessage);
                return false;
            }
        }
        return true;
    }

    /**
     * 评估单个前置条件
     */
    private boolean evaluatePrecondition(PreconditionSpec precondition, Path workDir) {
        return switch (precondition.getType()) {
            case "file_exists" -> {
                String resolvedPath = resolveVariables(precondition.getPath(), workDir);
                yield Files.exists(Paths.get(resolvedPath));
            }
            case "dir_exists" -> {
                String resolvedPath = resolveVariables(precondition.getPath(), workDir);
                yield Files.isDirectory(Paths.get(resolvedPath));
            }
            case "env_var" -> {
                String envValue = System.getenv(precondition.getVar());
                if (envValue == null) {
                    yield false;
                }
                yield precondition.getValue() == null || precondition.getValue().equals(envValue);
            }
            case "command_exists" -> {
                yield isCommandAvailable(precondition.getCommand());
            }
            default -> {
                log.warn("Unknown precondition type: {}", precondition.getType());
                yield false;
            }
        };
    }

    /**
     * 检查系统命令是否可用
     */
    private boolean isCommandAvailable(String command) {
        try {
            Process process = new ProcessBuilder("which", command)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析命令参数
     */
    private Map<String, String> resolveParameters(CommandContext context) {
        Map<String, String> parameterValues = new HashMap<>();

        for (int i = 0; i < spec.getParameters().size(); i++) {
            ParameterSpec param = spec.getParameters().get(i);
            String value = context.getArg(i);

            if (value == null || value.isEmpty()) {
                value = param.getDefaultValue();
            }

            if (value == null && param.isRequired()) {
                context.getOutputFormatter().printError(
                        "Required parameter missing: " + param.getName());
                return parameterValues;
            }

            if (value != null) {
                parameterValues.put(param.toEnvironmentVariableName(), value);
            }
        }

        // 将所有参数合并为 ARGUMENTS（对齐 Claude Code 标准）
        String allArguments = context.getArgsAsString();
        parameterValues.put("ARGUMENTS", allArguments);

        return parameterValues;
    }

    /**
     * 执行 Prompt 类型命令（对齐 Claude Code 标准）
     * 将 prompt 模板中的 $ARGUMENTS 替换后发送给 AI
     */
    private void executePrompt(CommandContext context, Map<String, String> parameterValues) {
        OutputFormatter out = context.getOutputFormatter();
        String promptContent = spec.getPrompt();

        // 替换 $ARGUMENTS 占位符
        String arguments = parameterValues.getOrDefault("ARGUMENTS", "");
        String resolvedPrompt = promptContent.replace("$ARGUMENTS", arguments);

        // 替换其他参数变量
        for (Map.Entry<String, String> entry : parameterValues.entrySet()) {
            resolvedPrompt = resolvedPrompt.replace("${" + entry.getKey() + "}", entry.getValue());
        }

        out.printInfo("Executing prompt command: " + spec.getName());
        log.debug("Sending prompt to AI: {}", resolvedPrompt);

        try {
            context.getEngineClient().runCommand(resolvedPrompt).block();
        } catch (Exception e) {
            out.printError("Failed to execute prompt command: " + e.getMessage());
            log.error("Prompt command execution failed: {}", spec.getName(), e);
        }
    }

    /**
     * 执行 Script 类型命令
     */
    private void executeScript(ExecutionSpec execution, CommandContext context,
                               Map<String, String> parameterValues) {
        OutputFormatter out = context.getOutputFormatter();
        Path workDir = context.getEngineClient().getWorkDir();

        String scriptContent = execution.getScript();
        if (execution.getScriptFile() != null && !execution.getScriptFile().isEmpty()) {
            try {
                String resolvedPath = resolveVariables(execution.getScriptFile(), workDir);
                scriptContent = Files.readString(Paths.get(resolvedPath));
            } catch (Exception e) {
                out.printError("Failed to read script file: " + e.getMessage());
                return;
            }
        }

        if (scriptContent == null || scriptContent.trim().isEmpty()) {
            out.printError("No script content to execute");
            return;
        }

        String resolvedScript = resolveVariables(scriptContent, workDir);

        try {
            ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", resolvedScript);

            // 设置工作目录
            String workingDir = execution.getWorkingDir();
            if (workingDir != null && !workingDir.isEmpty()) {
                processBuilder.directory(Paths.get(resolveVariables(workingDir, workDir)).toFile());
            } else {
                processBuilder.directory(workDir.toFile());
            }

            // 设置环境变量
            Map<String, String> env = processBuilder.environment();
            env.put("JIMI_WORK_DIR", workDir.toString());
            env.put("PROJECT_ROOT", workDir.toString());
            env.putAll(parameterValues);
            if (execution.getEnvironment() != null) {
                env.putAll(execution.getEnvironment());
            }

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // 读取输出
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.println(line);
                }
            }

            boolean finished = process.waitFor(execution.getTimeout(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                out.printWarning("Script timed out after " + execution.getTimeout() + " seconds");
            } else if (process.exitValue() != 0) {
                out.printError("Script exited with code: " + process.exitValue());
            } else {
                out.printSuccess("Script completed successfully");
            }
        } catch (Exception e) {
            out.printError("Script execution failed: " + e.getMessage());
            log.error("Script execution failed for command: {}", spec.getName(), e);
        }
    }

    /**
     * 执行 Agent 类型命令
     */
    private void executeAgent(ExecutionSpec execution, CommandContext context,
                              Map<String, String> parameterValues) {
        OutputFormatter out = context.getOutputFormatter();
        Path workDir = context.getEngineClient().getWorkDir();

        String taskDescription = resolveVariables(execution.getTask(), workDir);
        for (Map.Entry<String, String> entry : parameterValues.entrySet()) {
            taskDescription = taskDescription.replace("${" + entry.getKey() + "}", entry.getValue());
        }

        out.printInfo("Delegating to agent: " + execution.getAgent());
        log.debug("Agent task: {}", taskDescription);

        try {
            context.getEngineClient().runCommand(taskDescription).block();
        } catch (Exception e) {
            out.printError("Agent execution failed: " + e.getMessage());
            log.error("Agent execution failed for command: {}", spec.getName(), e);
        }
    }

    /**
     * 执行 Composite 类型命令
     */
    private void executeComposite(ExecutionSpec execution, CommandContext context,
                                  Map<String, String> parameterValues) {
        OutputFormatter out = context.getOutputFormatter();

        List<ExecutionSpec.ExecutionStep> steps = execution.getSteps();
        out.printInfo("Executing composite command: " + spec.getName()
                + " (" + steps.size() + " steps)");

        for (int i = 0; i < steps.size(); i++) {
            ExecutionSpec.ExecutionStep step = steps.get(i);
            String stepLabel = (i + 1) + "/" + steps.size();
            String stepDescription = step.getDescription() != null
                    ? step.getDescription() : step.getType();

            out.println("  [" + stepLabel + "] " + stepDescription);

            try {
                ExecutionSpec stepExecution = ExecutionSpec.builder()
                        .type(step.getType())
                        .script(step.getScript())
                        .timeout(step.getTimeout())
                        .build();

                executeScript(stepExecution, context, parameterValues);
            } catch (Exception e) {
                out.printError("  Step " + stepLabel + " failed: " + e.getMessage());
                if (!step.isContinueOnFailure()) {
                    out.printError("Aborting composite command");
                    return;
                }
                out.printWarning("  Continuing despite failure...");
            }
        }

        out.printSuccess("Composite command completed");
    }

    /**
     * 替换字符串中的变量引用
     */
    private String resolveVariables(String input, Path workDir) {
        if (input == null) {
            return null;
        }
        return input
                .replace("${JIMI_WORK_DIR}", workDir.toString())
                .replace("${PROJECT_ROOT}", workDir.toString())
                .replace("${HOME}", System.getProperty("user.home"));
    }

    /**
     * 获取底层命令规范
     *
     * @return 命令规范
     */
    public CustomCommandSpec getSpec() {
        return spec;
    }
}
