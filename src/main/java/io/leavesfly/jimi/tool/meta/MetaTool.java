package io.leavesfly.jimi.tool.meta;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.config.MetaToolConfig;
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
                Execute Java code to orchestrate multiple tool calls programmatically.
                
                Use this tool when you need to:
                - Process multiple files in a loop
                - Make conditional tool calls based on previous results
                - Perform batch operations
                - Transform data across multiple steps
                
                The code executes in an isolated JShell environment with access to a callTool() method.
                
                Available helper method:
                - String callTool(String toolName, String arguments) - Call a tool and get JSON result
                
                Example 1 - Loop through files:
                ```java
                String[] files = {"file1.txt", "file2.txt"};
                StringBuilder result = new StringBuilder();
                for (String file : files) {
                    String content = callTool("ReadFile", "{\\"path\\":\\"" + file + "\\"}");
                    result.append(content).append("\\n---\\n");
                }
                return result.toString();
                ```
                
                Example 2 - Conditional execution:
                ```java
                String osInfo = callTool("Bash", "{\\"command\\":\\"uname -s\\"}");
                if (osInfo.contains("Linux")) {
                    return callTool("Bash", "{\\"command\\":\\"apt list --installed\\"}");
                } else {
                    return callTool("Bash", "{\\"command\\":\\"brew list\\"}");
                }
                ```
                
                IMPORTANT:
                - The last expression value in your code will be returned as the result
                - Intermediate tool call results are NOT added to conversation history
                - Code execution timeout: 30 seconds
                - Use return statement to explicitly return a value
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
