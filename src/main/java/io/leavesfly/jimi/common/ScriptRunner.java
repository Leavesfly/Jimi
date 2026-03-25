package io.leavesfly.jimi.common;

import io.leavesfly.jimi.core.interaction.approval.Approval;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.tool.core.bash.Bash;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 统一脚本执行器
 *
 * 为 Hooks、Custom Commands 提供统一的脚本执行能力。
 */
@Slf4j
@Service
public class ScriptRunner {

    @Autowired
    private ApplicationContext applicationContext;

    private static final int DEFAULT_TIMEOUT = 60;

    private static final Map<String, String> SCRIPT_EXECUTORS = Map.of(
        "bash", "/bin/bash",
        "sh", "/bin/sh",
        "python", "python3",
        "python3", "python3",
        "node", "node",
        "ruby", "ruby"
    );

    private static final Map<String, String> EXTENSION_TO_TYPE = Map.of(
        ".sh", "bash",
        ".bash", "bash",
        ".py", "python",
        ".js", "node",
        ".rb", "ruby"
    );

    /**
     * 执行内联脚本（用于 Hooks 和 Custom Commands）
     *
     * @param script      脚本内容
     * @param workDir     工作目录
     * @param timeout     超时时间（秒），0 表示使用默认值
     * @param environment 额外的环境变量（可为 null）
     */
    public Mono<Void> runInlineScript(String script, Path workDir, int timeout, Map<String, String> environment) {
        return Mono.fromRunnable(() -> {
            try {
                int effectiveTimeout = timeout > 0 ? timeout : DEFAULT_TIMEOUT;

                ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-c", script);
                if (workDir != null) {
                    processBuilder.directory(workDir.toFile());
                }
                processBuilder.redirectErrorStream(true);

                if (environment != null && !environment.isEmpty()) {
                    processBuilder.environment().putAll(environment);
                }

                Process process = processBuilder.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[Script] {}", line);
                    }
                }

                boolean completed = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    log.error("Script execution timed out after {}s", effectiveTimeout);
                    return;
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    log.error("Script failed with exit code {}", exitCode);
                } else {
                    log.debug("Script executed successfully");
                }
            } catch (Exception e) {
                log.error("Failed to execute inline script", e);
            }
        });
    }

    /**
     * 执行脚本文件（用于 Skills）
     *
     * @param scriptPath  脚本文件路径
     * @param scriptType  脚本类型（可为 null，自动推断）
     * @param timeout     超时时间（秒），0 表示使用默认值
     * @param environment 额外的环境变量（可为 null）
     */
    public Mono<ToolResult> runScriptFile(Path scriptPath, String scriptType, int timeout,
                                          Map<String, String> environment) {
        try {
            String effectiveType = (scriptType != null && !scriptType.isEmpty())
                    ? scriptType.toLowerCase()
                    : inferScriptType(scriptPath);

            String command = buildCommand(effectiveType, scriptPath, environment);
            int effectiveTimeout = timeout > 0 ? timeout : DEFAULT_TIMEOUT;

            Bash bash = applicationContext.getBean(Bash.class);
            bash.setApproval(new Approval(true));

            Bash.Params params = Bash.Params.builder()
                    .command(command)
                    .timeout(effectiveTimeout)
                    .build();

            return bash.execute(params);
        } catch (Exception e) {
            log.error("Failed to execute script file: {}", scriptPath, e);
            return Mono.just(ToolResult.error(
                    "Script execution failed: " + e.getMessage(),
                    "Execution error"
            ));
        }
    }

    /**
     * 替换脚本中的变量占位符
     *
     * @param text      包含 ${KEY} 占位符的文本
     * @param variables 变量映射（KEY -> value）
     * @return 替换后的文本
     */
    public String replaceVariables(String text, Map<String, String> variables) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * 根据文件扩展名推断脚本类型
     */
    public String inferScriptType(Path scriptPath) {
        String fileName = scriptPath.getFileName().toString();
        for (Map.Entry<String, String> entry : EXTENSION_TO_TYPE.entrySet()) {
            if (fileName.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "bash";
    }

    private String buildCommand(String scriptType, Path scriptPath, Map<String, String> environment) {
        String executor = SCRIPT_EXECUTORS.getOrDefault(scriptType, "/bin/bash");
        String absolutePath = scriptPath.toAbsolutePath().toString();

        if (environment != null && !environment.isEmpty()) {
            StringBuilder command = new StringBuilder();
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                command.append(entry.getKey())
                       .append("=")
                       .append(escapeShellValue(entry.getValue()))
                       .append(" ");
            }
            command.append(executor).append(" ").append(absolutePath);
            return command.toString();
        }

        return executor + " " + absolutePath;
    }

    private String escapeShellValue(String value) {
        if (value.contains(" ") || value.contains("\"") || value.contains("'")) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return value;
    }
}
