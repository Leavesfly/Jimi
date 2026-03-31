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
                在隔离 JShell 环境中执行 Java 代码的通用编程执行器。
                可直接完成纯计算/转换任务，也可通过 callTool 编排多个工具调用，中间结果不进入对话历史。

                ## 何时使用

                优先选择 MetaTool：
                - 纯编程任务（字符串解析、格式转换、数学计算、JSON/CSV 处理等）
                - 需要循环/批量操作多个对象（文件、URL、记录等）
                - 多工具链式调用（前一步输出作为下一步输入）
                - 条件分支：根据运行时结果决定后续路径
                - 聚合多个工具结果后一次性返回

                不适用：1-2 次简单调用；需要用户确认中间结果。

                ## API

                ```java
                // 调用已注册工具，arguments 为 JSON 字符串，返回输出或 "Error: ..."
                String callTool(String toolName, String arguments)
                ```

                ## 示例

                **纯计算（CSV 转 JSON）：**
                ```java
                String[] rows = {"Alice,30", "Bob,25"};
                StringBuilder sb = new StringBuilder("[");
                for (String r : rows) {
                    String[] p = r.split(",");
                    sb.append(String.format("{\"name\":\"%s\",\"age\":%s},", p[0], p[1]));
                }
                return sb.deleteCharAt(sb.length()-1).append("]").toString();
                ```

                **批量读文件：**
                ```java
                String[] files = {"a.java", "b.java"};
                StringBuilder sb = new StringBuilder();
                for (String f : files)
                    sb.append("// ").append(f).append("\n")
                      .append(callTool("ReadFile", "{\"path\":\"" + f + "\"}")).append("\n");
                return sb.toString();
                ```

                **链式流（读→改→写）：**
                ```java
                String c = callTool("ReadFile", "{\"path\":\"cfg.json\"}");
                return callTool("WriteFile", "{\"path\":\"cfg.json\",\"content\":\"" +
                    c.replace("false", "true").replace("\"", "\\\"") + "\"}");
                ```

                **条件分支：**
                ```java
                String os = callTool("Bash", "{\"command\":\"uname -s\"}").trim();
                return callTool("Bash", os.equals("Darwin")
                    ? "{\"command\":\"brew list\"}"
                    : "{\"command\":\"apt list --installed\"}");
                ```

                ## 约束
                - 必须用 `return` 返回 String 结果
                - 超时 30 秒；仅 JDK 标准库；禁止 `System.exit`、`ProcessBuilder`
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
