package io.leavesfly.jimi.tool.core.meta;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.config.info.MetaToolConfig;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * MetaTool - 编程式工具调用
 * 
 * 允许 LLM 生成 Java 代码来编排多个工具调用
 * 减少 context token 消耗，支持复杂的编排逻辑
 */
@Slf4j
@Component
@Scope("prototype")
public class MetaTool extends AbstractTool<MetaTool.Params> {
    
    private final MetaToolConfig config;
    private final JShellCodeExecutor executor;
    
    // 运行时设置的工具注册表
    private ToolRegistry toolRegistry;
    
    @Autowired
    public MetaTool(MetaToolConfig config) {
        super(
                "MetaTool",
                buildDescription(),
                Params.class
        );
        this.config = config;
        this.executor = new JShellCodeExecutor();
    }
    
    /**
     * 设置工具注册表（由 MetaToolProvider 调用）
     */
    public void setToolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }
    
    /**
     * 构建工具描述
     */
    private static String buildDescription() {
        return """
                在隔离的 JShell 环境中执行 Java 代码，通过一次工具调用编排多个工具，节省 token 和对话轮次。
                中间工具调用结果不进入对话历史。

                ## 使用时机

                满足以下任意条件时优先使用 MetaTool：
                - 预计需要 **3 次及以上**工具调用才能完成任务
                - 需要对列表/集合做**循环**操作（如批量读取多个文件）
                - 需要根据前一步结果**条件分支**决定下一步操作
                - 需要在多个工具间**链式传递**数据

                以下情况**不适合**使用：
                - 1-2 次工具调用即可完成的简单任务
                - 需要将中间结果展示给用户确认时

                ## API

                ```java
                // 调用任意已注册工具，arguments 为 JSON 字符串，返回工具 output 或 "Error: <message>"
                String callTool(String toolName, String arguments)
                ```

                ## 示例 1：批量读取文件

                ```java
                String[] files = {"src/A.java", "src/B.java", "src/C.java"};
                StringBuilder sb = new StringBuilder();
                for (String f : files) {
                    sb.append("// ").append(f).append("\\n");
                    sb.append(callTool("ReadFile", "{\\"path\\":\\"" + f + "\\"}")).append("\\n");
                }
                return sb.toString();
                ```

                ## 示例 2：条件分支

                ```java
                String os = callTool("Bash", "{\\"command\\":\\"uname -s\\"}").trim();
                if (os.equals("Darwin")) {
                    return callTool("Bash", "{\\"command\\":\\"brew list\\"}");
                } else {
                    return callTool("Bash", "{\\"command\\":\\"apt list --installed\\"}");
                }
                ```

                ## 示例 3：读取-修改-写入

                ```java
                String content = callTool("ReadFile", "{\\"path\\":\\"config.json\\"}");
                String updated = content.replace("\\"debug\\": false", "\\"debug\\": true");
                return callTool("WriteFile", "{\\"path\\":\\"config.json\\", \\"content\\":\\"" + updated.replace("\\"", "\\\\\\"") + "\\"}");
                ```

                ## 约束

                - 必须使用 `return` 语句返回最终结果（String 类型）
                - 执行超时：默认 30 秒
                - 仅 JDK 标准库可用；不可调用 `System.exit`、`ProcessBuilder` 等危险 API
                """;
    }
    
    /**
     * 工具参数
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        
        @JsonProperty("code")
        @JsonPropertyDescription("Java code to execute. The code should return a string value as the final result.")
        private String code;
        
        @JsonProperty("timeout")
        @JsonPropertyDescription("Execution timeout in seconds (default: 30, max: 60)")
        private Integer timeout = 30;
        
        @JsonProperty("allowed_tools")
        @JsonPropertyDescription("List of tool names allowed to be called (optional, null means all tools allowed)")
        private List<String> allowedTools;
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        log.info("MetaTool: Starting code execution");
        
        // 前置检查：ToolRegistry 必须已注入
        if (toolRegistry == null) {
            String error = "MetaTool is not properly initialized: ToolRegistry is null. " +
                    "Ensure MetaToolProvider has injected the ToolRegistry before execution.";
            log.error("MetaTool: {}", error);
            return Mono.just(ToolResult.error(error, "Initialization error"));
        }
        
        // 验证参数
        String validationError = validateParamsInternal(params);
        if (validationError != null) {
            log.error("MetaTool: Parameter validation failed: {}", validationError);
            return Mono.just(ToolResult.error(validationError, "Parameter validation failed"));
        }
        
        // 验证代码安全性
        if (!JShellCodeExecutor.validateCodeSafety(params.getCode())) {
            String error = "Code contains potentially dangerous operations";
            log.error("MetaTool: {}", error);
            return Mono.just(ToolResult.error(error, "Security check failed"));
        }
        
        // 构建执行上下文
        CodeExecutionContext context = CodeExecutionContext.builder()
                .code(params.getCode())
                .timeout(Math.min(params.getTimeout(), config.getMaxExecutionTime()))
                .allowedTools(params.getAllowedTools())
                .toolRegistry(toolRegistry)
                .logExecutionDetails(config.isLogExecutionDetails())
                .build();
        
        if (config.isLogExecutionDetails()) {
            log.info("MetaTool: Execution context - timeout: {}s, allowed tools: {}", 
                    context.getTimeout(), 
                    context.getAllowedTools() != null ? context.getAllowedTools().size() : "all");
        }
        
        // 执行代码
        return executor.execute(context)
                .map(result -> {
                    if (result.startsWith("Error:") || result.startsWith("Exception:")) {
                        return ToolResult.error(result, "Code execution failed");
                    }
                    return ToolResult.ok(result, "Code executed successfully");
                })
                .doOnSuccess(result -> {
                    if (result.isOk()) {
                        log.info("MetaTool: Code execution completed successfully, result length: {} chars", 
                                result.getOutput().length());
                    } else {
                        log.error("MetaTool: Code execution failed: {}", result.getMessage());
                    }
                })
                .doOnError(e -> log.error("MetaTool: Unexpected error", e))
                .onErrorResume(e -> Mono.just(ToolResult.error(
                        "Unexpected error: " + e.getMessage(),
                        "Execution error"
                )));
    }
    
    /**
     * 验证参数（内部方法）
     */
    private String validateParamsInternal(Params params) {
        if (params.getCode() == null || params.getCode().trim().isEmpty()) {
            return "Code parameter is required and cannot be empty";
        }
        
        if (params.getCode().length() > config.getMaxCodeLength()) {
            return String.format("Code length (%d) exceeds maximum allowed length (%d)",
                    params.getCode().length(), config.getMaxCodeLength());
        }
        
        if (params.getTimeout() != null && params.getTimeout() < 1) {
            return "Timeout must be at least 1 second";
        }
        
        if (params.getTimeout() != null && params.getTimeout() > config.getMaxExecutionTime()) {
            return String.format("Timeout (%d) exceeds maximum allowed (%d)",
                    params.getTimeout(), config.getMaxExecutionTime());
        }
        
        return null;
    }
    
    @Override
    public boolean validateParams(Params params) {
        return validateParamsInternal(params) == null;
    }
}
