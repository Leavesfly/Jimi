package io.leavesfly.jimi.adk.core.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Skill 脚本执行器
 * 
 * 职责：
 * - 执行 Skill 关联的脚本文件
 * - 支持多种脚本类型（bash, python, node 等）
 * - 提供环境变量注入和超时控制
 * - 处理脚本执行结果和错误
 */
@Slf4j
public class SkillScriptExecutor {
    
    private final SkillConfig skillConfig;
    
    private static final int DEFAULT_TIMEOUT = 60;
    
    private static final Map<String, String> SCRIPT_EXECUTORS = Map.of(
        "bash", "/bin/bash",
        "sh", "/bin/sh",
        "python", "python3",
        "python3", "python3",
        "python2", "python2",
        "node", "node",
        "ruby", "ruby",
        "perl", "perl"
    );
    
    private static final Map<String, String> EXTENSION_TO_TYPE = Map.of(
        ".sh", "bash",
        ".bash", "bash",
        ".py", "python",
        ".js", "node",
        ".rb", "ruby",
        ".pl", "perl"
    );
    
    public SkillScriptExecutor(SkillConfig skillConfig) {
        this.skillConfig = skillConfig;
    }
    
    public SkillScriptExecutor() {
        this(null);
    }
    
    /**
     * 执行 Skill 脚本
     *
     * @param skill   Skill 规格
     * @param workDir 工作目录
     * @return 执行结果
     */
    public ScriptResult executeScript(SkillSpec skill, Path workDir) {
        if (!shouldExecuteScript(skill)) {
            log.debug("Skill '{}' does not require script execution", skill.getName());
            return ScriptResult.success("No script to execute", "");
        }
        
        try {
            Path scriptPath = resolveScriptPath(skill, workDir);
            
            if (!Files.exists(scriptPath)) {
                String error = String.format("Script file not found: %s", scriptPath);
                log.error(error);
                return ScriptResult.failure(error, "");
            }
            
            if (!Files.isReadable(scriptPath)) {
                String error = String.format("Script file not readable: %s", scriptPath);
                log.error(error);
                return ScriptResult.failure(error, "");
            }
            
            String scriptType = determineScriptType(skill, scriptPath);
            String command = buildExecutionCommand(scriptType, scriptPath, skill.getScriptEnv());
            int timeout = determineTimeout(skill);
            
            log.info("Executing script for skill '{}': {} (timeout: {}s)", 
                    skill.getName(), scriptPath, timeout);
            
            return executeCommand(command, workDir, timeout);
            
        } catch (Exception e) {
            log.error("Failed to prepare script execution for skill '{}'", skill.getName(), e);
            return ScriptResult.failure("Failed to prepare script: " + e.getMessage(), "");
        }
    }
    
    private boolean shouldExecuteScript(SkillSpec skill) {
        if (!isScriptExecutionEnabled()) {
            log.debug("Script execution is disabled globally");
            return false;
        }
        if (skill.getScriptPath() == null || skill.getScriptPath().isEmpty()) {
            return false;
        }
        return skill.isAutoExecute();
    }
    
    private Path resolveScriptPath(SkillSpec skill, Path workDir) {
        String scriptPath = skill.getScriptPath();
        
        Path path = Path.of(scriptPath);
        if (path.isAbsolute()) {
            return path;
        }
        
        Path skillDir = skill.getSkillFilePath() != null 
                ? skill.getSkillFilePath().getParent() 
                : workDir;
        
        if (skillDir == null) {
            skillDir = workDir;
        }
        
        return skillDir.resolve(scriptPath).normalize();
    }
    
    private String determineScriptType(SkillSpec skill, Path scriptPath) {
        if (skill.getScriptType() != null && !skill.getScriptType().isEmpty()) {
            return skill.getScriptType().toLowerCase();
        }
        
        String fileName = scriptPath.getFileName().toString();
        for (Map.Entry<String, String> entry : EXTENSION_TO_TYPE.entrySet()) {
            if (fileName.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        
        log.debug("Unable to determine script type for '{}', defaulting to bash", fileName);
        return "bash";
    }
    
    private String buildExecutionCommand(String scriptType, Path scriptPath, Map<String, String> env) {
        String executor = SCRIPT_EXECUTORS.getOrDefault(scriptType, "/bin/bash");
        String absolutePath = scriptPath.toAbsolutePath().toString();
        
        if (env != null && !env.isEmpty()) {
            StringBuilder cmd = new StringBuilder();
            for (Map.Entry<String, String> entry : env.entrySet()) {
                cmd.append(entry.getKey())
                   .append("=")
                   .append(escapeShellValue(entry.getValue()))
                   .append(" ");
            }
            cmd.append(executor).append(" ").append(absolutePath);
            return cmd.toString();
        }
        
        return executor + " " + absolutePath;
    }
    
    private String escapeShellValue(String value) {
        if (value.contains(" ") || value.contains("\"") || value.contains("'")) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return value;
    }
    
    private int determineTimeout(SkillSpec skill) {
        if (skill.getScriptTimeout() > 0) {
            return skill.getScriptTimeout();
        }
        if (skillConfig != null && skillConfig.getScriptExecution() != null) {
            int globalTimeout = skillConfig.getScriptExecution().getTimeout();
            if (globalTimeout > 0) {
                return globalTimeout;
            }
        }
        return DEFAULT_TIMEOUT;
    }
    
    /**
     * 执行命令（通过 ProcessBuilder）
     */
    private ScriptResult executeCommand(String command, Path workDir, int timeout) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);
            
            if (!completed) {
                process.destroyForcibly();
                return ScriptResult.failure("Script execution timed out after " + timeout + "s",
                    output.toString());
            }
            
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return ScriptResult.success("Script executed successfully (exit code: 0)",
                    output.toString());
            } else {
                return ScriptResult.failure(
                    "Script exited with code: " + exitCode,
                    output.toString());
            }
            
        } catch (Exception e) {
            log.error("Failed to execute command", e);
            return ScriptResult.failure("Command execution failed: " + e.getMessage(), "");
        }
    }
    
    private boolean isScriptExecutionEnabled() {
        if (skillConfig != null && skillConfig.getScriptExecution() != null) {
            return skillConfig.getScriptExecution().isEnabled();
        }
        return true;
    }
    
    /**
     * 脚本执行结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScriptResult {
        private boolean success;
        private String message;
        private String output;
        
        public static ScriptResult success(String message, String output) {
            return ScriptResult.builder()
                    .success(true)
                    .message(message)
                    .output(output)
                    .build();
        }
        
        public static ScriptResult failure(String message, String output) {
            return ScriptResult.builder()
                    .success(false)
                    .message(message)
                    .output(output)
                    .build();
        }
    }
}
