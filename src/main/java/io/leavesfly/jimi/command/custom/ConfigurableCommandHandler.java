package io.leavesfly.jimi.command.custom;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.core.interaction.approval.Approval;
import io.leavesfly.jimi.core.interaction.approval.ApprovalResponse;
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
 * 基于 CustomCommandSpec 动态创建的命令处理器
 * 支持三种执行类型:
 * 1. script: 执行脚本
 * 2. agent: 委托给 Agent
 * 3. composite: 组合多个命令/脚本
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
    public boolean isAvailable(CommandContext context) {
        return spec.isEnabled();
    }
    
    @Override
    public void execute(CommandContext context) throws Exception {
        OutputFormatter out = context.getOutputFormatter();
        
        try {
            // 1. 验证前置条件
            validatePreconditions(spec.getPreconditions(), context);
            
            // 2. 解析参数
            Map<String, Object> params = parseParameters(spec, context);
            
            // 3. 请求审批 (如果需要)
            if (spec.isRequireApproval()) {
                boolean approved = requestApproval(context, spec.getName(), spec.getDescription());
                if (!approved) {
                    out.printWarning("命令执行被拒绝");
                    return;
                }
            }
            
            // 4. 根据类型执行
            ExecutionSpec execution = spec.getExecution();
            switch (execution.getType()) {
                case "script":
                    executeScript(execution, params, context);
                    break;
                case "agent":
                    executeAgent(execution, params, context);
                    break;
                case "composite":
                    executeComposite(execution, params, context);
                    break;
                default:
                    throw new IllegalStateException("Unknown execution type: " + execution.getType());
            }
            
        } catch (PreconditionFailedException e) {
            out.printError("前置条件检查失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to execute custom command: {}", spec.getName(), e);
            out.printError("命令执行失败: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * 验证前置条件
     */
    private void validatePreconditions(List<PreconditionSpec> preconditions, CommandContext context) 
            throws PreconditionFailedException {
        if (preconditions == null || preconditions.isEmpty()) {
            return;
        }
        
        for (PreconditionSpec precondition : preconditions) {
            if (!checkPrecondition(precondition, context)) {
                String errorMsg = precondition.getErrorMessage() != null ? 
                        precondition.getErrorMessage() : 
                        "Precondition failed: " + precondition.getType();
                throw new PreconditionFailedException(errorMsg);
            }
        }
    }
    
    /**
     * 检查单个前置条件
     */
    private boolean checkPrecondition(PreconditionSpec precondition, CommandContext context) {
        switch (precondition.getType()) {
            case "file_exists":
                Path filePath = resolvePath(precondition.getPath(), context);
                return Files.exists(filePath) && Files.isRegularFile(filePath);
                
            case "dir_exists":
                Path dirPath = resolvePath(precondition.getPath(), context);
                return Files.exists(dirPath) && Files.isDirectory(dirPath);
                
            case "env_var":
                String value = System.getenv(precondition.getVar());
                if (value == null) {
                    return false;
                }
                if (precondition.getValue() != null) {
                    return value.equals(precondition.getValue());
                }
                return true;
                
            case "command_exists":
                return isCommandAvailable(precondition.getCommand());
                
            default:
                log.warn("Unknown precondition type: {}", precondition.getType());
                return false;
        }
    }
    
    /**
     * 检查命令是否可用
     */
    private boolean isCommandAvailable(String command) {
        try {
            Process process = Runtime.getRuntime().exec(
                new String[]{"which", command}
            );
            return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 解析命令参数
     */
    private Map<String, Object> parseParameters(CustomCommandSpec spec, CommandContext context) {
        Map<String, Object> params = new HashMap<>();
        
        if (spec.getParameters() == null || spec.getParameters().isEmpty()) {
            return params;
        }
        
        for (int i = 0; i < spec.getParameters().size(); i++) {
            ParameterSpec paramSpec = spec.getParameters().get(i);
            String value;
            
            // 从命令参数中获取值
            if (i < context.getArgCount()) {
                value = context.getArg(i);
            } else {
                value = paramSpec.getDefaultValue();
            }
            
            // 如果必需参数缺失
            if (value == null && paramSpec.isRequired()) {
                throw new IllegalArgumentException(
                    "Required parameter missing: " + paramSpec.getName()
                );
            }
            
            // 类型转换
            Object typedValue = convertParameter(value, paramSpec.getType());
            params.put(paramSpec.getName(), typedValue);
        }
        
        return params;
    }
    
    /**
     * 参数类型转换
     */
    private Object convertParameter(String value, String type) {
        if (value == null) {
            return null;
        }
        
        switch (type) {
            case "boolean":
                return Boolean.parseBoolean(value);
            case "integer":
                return Integer.parseInt(value);
            case "path":
                return Paths.get(value);
            case "string":
            default:
                return value;
        }
    }
    
    /**
     * 请求审批
     */
    private boolean requestApproval(CommandContext context, String action, String description) {
        Approval approval = context.getSoul().getRuntime().getApproval();
        if (approval == null) {
            return true; // 如果没有审批系统,默认批准
        }
        
        try {
            ApprovalResponse response = approval.requestApproval(
                "custom-command-" + spec.getName(),
                action,
                description
            ).block();
            
            return response != ApprovalResponse.REJECT;
        } catch (Exception e) {
            log.error("Failed to request approval", e);
            return false;
        }
    }
    
    /**
     * 执行脚本类型命令
     */
    private void executeScript(ExecutionSpec execution, Map<String, Object> params, CommandContext context) 
            throws Exception {
        OutputFormatter out = context.getOutputFormatter();
        
        // 获取脚本内容
        String script = getScriptContent(execution, context);
        if (script == null || script.trim().isEmpty()) {
            throw new IllegalStateException("Script content is empty");
        }
        
        // 替换变量
        script = replaceVariables(script, params, context);
        
        // 获取工作目录
        Path workDir = getWorkingDirectory(execution, context);
        
        // 构建环境变量
        Map<String, String> env = buildEnvironment(execution, params, context);
        
        // 执行脚本
        out.println("执行脚本命令...");
        log.debug("Executing script: {}", script);
        
        executeShellScript(script, workDir, env, execution.getTimeout(), out);
    }
    
    /**
     * 获取脚本内容
     */
    private String getScriptContent(ExecutionSpec execution, CommandContext context) throws Exception {
        // 优先使用脚本文件
        if (execution.getScriptFile() != null && !execution.getScriptFile().trim().isEmpty()) {
            Path scriptPath = resolvePath(execution.getScriptFile(), context);
            if (!Files.exists(scriptPath)) {
                throw new IllegalStateException("Script file not found: " + scriptPath);
            }
            return Files.readString(scriptPath);
        }
        
        // 使用内联脚本
        return execution.getScript();
    }
    
    /**
     * 执行 Shell 脚本
     */
    private void executeShellScript(String script, Path workDir, Map<String, String> env, 
                                    int timeout, OutputFormatter out) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", script);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        
        // 设置环境变量
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }
        
        Process process = pb.start();
        
        // 读取输出
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.println(line);
            }
        }
        
        // 等待完成
        boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);
        
        if (!completed) {
            process.destroyForcibly();
            throw new Exception("Script execution timeout (" + timeout + "s)");
        }
        
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new Exception("Script failed with exit code: " + exitCode);
        }
        
        out.printSuccess("脚本执行成功");
    }
    
    /**
     * 执行 Agent 委托类型命令
     */
    private void executeAgent(ExecutionSpec execution, Map<String, Object> params, CommandContext context) 
            throws Exception {
        OutputFormatter out = context.getOutputFormatter();
        
        String agentName = execution.getAgent();
        String task = replaceVariables(execution.getTask(), params, context);
        
        out.println("委托给 Agent: " + agentName);
        out.println("任务: " + task);
        
        // TODO: 实现 Agent 委托逻辑
        // 这里需要使用 SubAgentTool 工具来委托给子 Agent
        throw new UnsupportedOperationException("Agent delegation not yet implemented");
    }
    
    /**
     * 执行组合类型命令
     */
    private void executeComposite(ExecutionSpec execution, Map<String, Object> params, CommandContext context) 
            throws Exception {
        OutputFormatter out = context.getOutputFormatter();
        
        List<CompositeStepSpec> steps = execution.getSteps();
        out.println("执行组合命令 (" + steps.size() + " 步骤)...");
        
        for (int i = 0; i < steps.size(); i++) {
            CompositeStepSpec step = steps.get(i);
            out.println("\n[" + (i + 1) + "/" + steps.size() + "] " + 
                       (step.getDescription() != null ? step.getDescription() : step.getType()));
            
            try {
                executeCompositeStep(step, params, context);
            } catch (Exception e) {
                if (step.isContinueOnFailure()) {
                    out.printWarning("步骤失败但继续执行: " + e.getMessage());
                } else {
                    throw e;
                }
            }
        }
        
        out.printSuccess("组合命令执行完成");
    }
    
    /**
     * 执行组合步骤
     */
    private void executeCompositeStep(CompositeStepSpec step, Map<String, Object> params, CommandContext context) 
            throws Exception {
        switch (step.getType()) {
            case "command":
                // 执行元命令
                String command = replaceVariables(step.getCommand(), params, context);
                // TODO: 执行元命令
                log.info("Would execute command: {}", command);
                break;
                
            case "script":
                // 执行脚本
                String script = replaceVariables(step.getScript(), params, context);
                Path workDir = context.getSoul().getRuntime().getWorkDir();
                executeShellScript(script, workDir, null, 60, context.getOutputFormatter());
                break;
                
            default:
                throw new IllegalArgumentException("Unknown step type: " + step.getType());
        }
    }
    
    /**
     * 替换变量
     */
    private String replaceVariables(String text, Map<String, Object> params, CommandContext context) {
        if (text == null) {
            return null;
        }
        
        String result = text;
        
        // 替换内置变量
        result = result.replace("${JIMI_WORK_DIR}", 
                context.getSoul().getRuntime().getWorkDir().toString());
        result = result.replace("${HOME}", System.getProperty("user.home"));
        
        // 替换参数变量
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = "${" + entry.getKey().toUpperCase().replace("-", "_") + "}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(key, value);
        }
        
        return result;
    }
    
    /**
     * 解析路径
     */
    private Path resolvePath(String pathStr, CommandContext context) {
        String resolved = replaceVariables(pathStr, new HashMap<>(), context);
        Path path = Paths.get(resolved);
        
        if (!path.isAbsolute()) {
            path = context.getSoul().getRuntime().getWorkDir().resolve(path);
        }
        
        return path;
    }
    
    /**
     * 获取工作目录
     */
    private Path getWorkingDirectory(ExecutionSpec execution, CommandContext context) {
        if (execution.getWorkingDir() != null && !execution.getWorkingDir().trim().isEmpty()) {
            return resolvePath(execution.getWorkingDir(), context);
        }
        return context.getSoul().getRuntime().getWorkDir();
    }
    
    /**
     * 构建环境变量
     */
    private Map<String, String> buildEnvironment(ExecutionSpec execution, Map<String, Object> params, 
                                                  CommandContext context) {
        Map<String, String> env = new HashMap<>();
        
        // 复制执行配置中的环境变量
        if (execution.getEnvironment() != null) {
            execution.getEnvironment().forEach((key, value) -> {
                env.put(key, replaceVariables(value, params, context));
            });
        }
        
        // 添加参数作为环境变量
        params.forEach((key, value) -> {
            String envKey = key.toUpperCase().replace("-", "_");
            env.put(envKey, value != null ? value.toString() : "");
        });
        
        return env;
    }
    
    /**
     * 前置条件失败异常
     */
    public static class PreconditionFailedException extends Exception {
        public PreconditionFailedException(String message) {
            super(message);
        }
    }
}
