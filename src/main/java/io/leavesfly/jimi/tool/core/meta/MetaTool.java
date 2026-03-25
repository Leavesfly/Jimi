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
                执行 Java 代码以编程方式编排多个工具调用，用一次工具调用替代多轮对话，大幅节省 token 消耗。
                
                ## 何时使用此工具（重要）
                
                当你发现需要执行以下操作时，**应该优先选择 MetaTool 而不是逐个调用其他工具**：
                
                1. **批量处理多个文件**：需要读取、编辑、删除 3 个及以上文件时
                2. **循环操作**：需要对列表中的每个元素执行相同操作时
                3. **条件分支执行**：需要根据前一步结果决定下一步调用哪个工具时
                4. **多步骤数据转换**：需要将数据在多个工具间传递、转换时
                5. **减少对话轮次**：预计需要 3 次以上工具调用才能完成任务时
                
                ## 何时不适合使用此工具
                
                - 只需调用 1-2 个工具即可完成的简单任务
                - 任务逻辑过于复杂，生成代码反而不如直接调用清晰时
                - 需要中间结果展示给用户确认时
                
                ## 工作原理
                
                代码在隔离的 JShell 环境中执行，可通过 callTool() 方法调用其他已注册的工具。
                
                ## 可用辅助方法
                
                - String callTool(String toolName, String arguments) - 调用指定工具，arguments 为 JSON 格式参数字符串，返回工具执行结果
                
                ## 示例 1：批量读取多个文件
                
                ```java
                String[] files = {"file1.txt", "file2.txt", "file3.txt"};
                StringBuilder result = new StringBuilder();
                for (String file : files) {
                    String content = callTool("ReadFile", "{\\"path\\":\\"" + file + "\\"}");
                    result.append("=== ").append(file).append(" ===\\n");
                    result.append(content).append("\\n\\n");
                }
                return result.toString();
                ```
                
                ## 示例 2：条件执行不同命令
                
                ```java
                String osInfo = callTool("Bash", "{\\"command\\":\\"uname -s\\"}");
                if (osInfo.contains("Linux")) {
                    return callTool("Bash", "{\\"command\\":\\"apt list --installed\\"}");
                } else if (osInfo.contains("Darwin")) {
                    return callTool("Bash", "{\\"command\\":\\"brew list\\"}");
                } else {
                    return "Unsupported OS: " + osInfo;
                }
                ```
                
                ## 示例 3：链式数据处理
                
                ```java
                // 读取配置 -> 解析 -> 根据配置执行操作
                String config = callTool("ReadFile", "{\\"path\\":\\"config.json\\"}");
                String filtered = callTool("Bash", "{\\"command\\":\\"echo '" + config + "' | jq '.items[]'\"}");
                return callTool("WriteFile", "{\\"path\\":\\"output.txt\\", \\"content\\":\\"" + filtered + "\"}");
                ```
                
                ## 重要说明
                
                - 使用 return 语句显式返回最终结果
                - 中间工具调用结果不会添加到对话历史，节省 token
                - 代码执行超时：30 秒（最大 60 秒）
                - 可通过 allowed_tools 参数限制可调用的工具范围
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
