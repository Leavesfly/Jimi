package io.leavesfly.jimi.adk.tools.shell;

import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.tools.base.AbstractTool;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Shell 命令执行工具
 */
@Slf4j
public class BashTool extends AbstractTool<BashTool.Params> {
    
    /**
     * 工作目录
     */
    private final Path workDir;
    
    /**
     * 默认超时时间（秒）
     */
    private static final int DEFAULT_TIMEOUT = 60;
    
    public BashTool(Path workDir) {
        super("bash", 
              "在 Shell 中执行命令。支持设置超时时间。", 
              Params.class);
        this.workDir = workDir;
    }
    
    @Override
    public boolean requiresApproval() {
        return true;  // Shell 命令需要审批
    }
    
    @Override
    public String getApprovalDescription(Params params) {
        return "执行命令: " + params.command;
    }
    
    @Override
    protected Mono<ToolResult> doExecute(Params params) {
        return Mono.fromCallable(() -> {
            int timeout = params.timeout != null ? params.timeout : DEFAULT_TIMEOUT;
            
            log.info("执行命令: {} (超时: {}s)", params.command, timeout);
            
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            
            // 根据操作系统选择 Shell
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb.command("cmd", "/c", params.command);
            } else {
                pb.command("/bin/bash", "-c", params.command);
            }
            
            Process process = pb.start();
            
            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            // 等待进程完成
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("命令执行超时（" + timeout + "秒）");
            }
            
            int exitCode = process.exitValue();
            String result = output.toString();
            
            if (exitCode == 0) {
                return ToolResult.success(result.isEmpty() ? "命令执行成功（无输出）" : result);
            } else {
                return ToolResult.error("命令执行失败 (退出码: " + exitCode + ")\n" + result);
            }
        });
    }
    
    /**
     * 工具参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        @JsonPropertyDescription("要执行的 Shell 命令")
        private String command;
        
        @JsonPropertyDescription("命令超时时间（秒），默认 60 秒")
        private Integer timeout;
    }
}
