package io.leavesfly.jimi.tool.bash;

import io.leavesfly.jimi.soul.approval.Approval;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.tool.ToolResultBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Bash 工具 - 执行 Shell 命令
 * 支持超时控制和输出流式读取
 * 
 * 使用 @Scope("prototype") 使每次获取都是新实例
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class Bash extends AbstractTool<Bash.Params> {
    
    private static final int MAX_TIMEOUT = 5 * 60; // 5分钟
    private static final String RUN_COMMAND_ACTION = "run shell command";
    
    private Approval approval;
    
    /**
     * 参数模型
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        /**
         * 要执行的 Bash 命令
         */
        private String command;
        
        /**
         * 超时时间（秒）
         */
        @Builder.Default
        private int timeout = 60;
    }
    
    /**
     * 默认构造函数（Spring 调用）
     */
    public Bash() {
        super(
            "Bash",
            "执行 bash 命令并支持超时控制。最大超时时间为 " + MAX_TIMEOUT + " 秒。",
            Params.class
        );
    }
    
    /**
     * 设置 Approval（运行时注入）
     */
    public void setApproval(Approval approval) {
        this.approval = approval;
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        return Mono.defer(() -> {
            // 验证参数
            if (params.command == null || params.command.trim().isEmpty()) {
                return Mono.just(ToolResult.error(
                    "Command is required. Please provide a valid command to execute.",
                    "Missing command"
                ));
            }
            
            // 验证超时参数
            if (params.timeout < 1 || params.timeout > MAX_TIMEOUT) {
                return Mono.just(ToolResult.error(
                    String.format("Invalid timeout: %d. Timeout must be between 1 and %d seconds.", params.timeout, MAX_TIMEOUT),
                    "Invalid timeout"
                ));
            }
            
            // 请求审批
            return approval.requestApproval("bash", RUN_COMMAND_ACTION, String.format("Run command `%s`", params.command))
                .flatMap(response -> {
                    if (response == io.leavesfly.jimi.soul.approval.ApprovalResponse.REJECT) {
                        return Mono.just(ToolResult.rejected());
                    }
                    
                    return executeCommand(params.command, params.timeout);
                });
        });
    }
    
    /**
     * 执行命令
     */
    private Mono<ToolResult> executeCommand(String command, int timeoutSeconds) {
        return Mono.fromCallable(() -> {
            ToolResultBuilder builder = new ToolResultBuilder();
            Process process = null;
            
            try {
                // 根据操作系统选择 shell
                String[] cmdArray;
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    cmdArray = new String[]{"cmd.exe", "/c", command};
                } else {
                    cmdArray = new String[]{"/bin/bash", "-c", command};
                }
                
                // 启动进程
                ProcessBuilder processBuilder = new ProcessBuilder(cmdArray);
                processBuilder.redirectErrorStream(true); // 合并 stdout 和 stderr
                process = processBuilder.start();
                
                // 读取输出
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.write(line + "\n");
                    }
                }
                
                // 等待完成（带超时）
                boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                
                if (!completed) {
                    // 超时，强制结束进程
                    process.destroyForcibly();
                    return builder.error(
                        String.format("Command killed by timeout (%ds)", timeoutSeconds),
                        String.format("Killed by timeout (%ds)", timeoutSeconds)
                    );
                }
                
                // 检查退出码
                int exitCode = process.exitValue();
                if (exitCode == 0) {
                    return builder.ok("Command executed successfully.");
                } else {
                    return builder.error(
                        String.format("Command failed with exit code: %d.", exitCode),
                        String.format("Failed with exit code: %d", exitCode)
                    );
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (process != null) {
                    process.destroyForcibly();
                }
                return builder.error(
                    "Command execution was interrupted",
                    "Interrupted"
                );
            } catch (Exception e) {
                log.error("Failed to execute command: {}", command, e);
                return builder.error(
                    String.format("Failed to execute command. Error: %s", e.getMessage()),
                    "Execution failed"
                );
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        });
    }
}
