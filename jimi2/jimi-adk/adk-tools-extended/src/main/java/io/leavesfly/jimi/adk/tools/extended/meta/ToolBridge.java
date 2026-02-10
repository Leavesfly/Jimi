package io.leavesfly.jimi.adk.tools.extended.meta;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具调用桥接
 * 
 * 在 JShell 环境中提供工具调用能力
 * 将 Reactor Mono 同步化，供同步代码使用
 */
@Slf4j
public class ToolBridge {
    
    private final ToolRegistry toolRegistry;
    private final List<String> allowedTools;
    private final boolean logExecutionDetails;
    private final ObjectMapper objectMapper;
    
    /**
     * 构造函数
     * 
     * @param toolRegistry 工具注册表
     * @param allowedTools 允许调用的工具列表（null 表示允许所有）
     * @param logExecutionDetails 是否记录执行详情
     */
    public ToolBridge(ToolRegistry toolRegistry, List<String> allowedTools, boolean logExecutionDetails) {
        this.toolRegistry = toolRegistry;
        this.allowedTools = allowedTools;
        this.logExecutionDetails = logExecutionDetails;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 调用工具（字符串参数）
     * 
     * @param toolName 工具名称
     * @param arguments JSON 格式的参数字符串
     * @return 工具执行结果：成功时返回 message 内容，失败时返回 "Error: <message>"
     */
    public String callTool(String toolName, String arguments) {
        if (logExecutionDetails) {
            log.info("ToolBridge: Calling tool '{}' with arguments: {}", toolName, 
                    arguments.length() > 200 ? arguments.substring(0, 200) + "..." : arguments);
        }
        
        // 检查工具是否在允许列表中
        if (allowedTools != null && !allowedTools.contains(toolName)) {
            String error = String.format("Tool '%s' is not in the allowed tools list", toolName);
            log.error(error);
            return "Error: " + error;
        }
        
        try {
            // 查找工具
            Optional<Tool<?>> toolOpt = toolRegistry.getTool(toolName);
            if (toolOpt.isEmpty()) {
                return "Error: Tool not found: " + toolName;
            }
            
            Tool<?> tool = toolOpt.get();
            
            // 反序列化参数并执行
            ToolResult result = executeTool(tool, arguments)
                    .block(Duration.ofSeconds(60));
            
            if (result == null) {
                return "Error: Tool execution returned null";
            }
            
            // 简化返回：成功时返回 message，失败时返回 Error 前缀
            if (result.isOk()) {
                String message = result.getMessage();
                if (logExecutionDetails) {
                    log.info("ToolBridge: Tool '{}' executed successfully, message length: {} chars", 
                            toolName, message != null ? message.length() : 0);
                }
                return message != null ? message : "";
            } else {
                String errorMsg = "Error: " + result.getMessage();
                if (logExecutionDetails) {
                    log.warn("ToolBridge: Tool '{}' failed: {}", toolName, result.getMessage());
                }
                return errorMsg;
            }
            
        } catch (Exception e) {
            log.error("ToolBridge: Error executing tool '{}'", toolName, e);
            return "Error: Tool execution failed: " + e.getMessage();
        }
    }
    
    /**
     * 执行工具（类型安全）
     */
    @SuppressWarnings("unchecked")
    private <P> reactor.core.publisher.Mono<ToolResult> executeTool(Tool<?> tool, String arguments) 
            throws JsonProcessingException {
        Tool<P> typedTool = (Tool<P>) tool;
        P params = (P) objectMapper.readValue(
                arguments == null || arguments.isEmpty() ? "{}" : arguments,
                tool.getParamsType()
        );
        return typedTool.execute(params);
    }
    
    /**
     * 调用工具（Map 参数）
     * 
     * @param toolName 工具名称
     * @param arguments 参数 Map
     * @return 工具执行结果：成功时返回 message 内容，失败时返回 "Error: <message>"
     */
    public String callTool(String toolName, Map<String, Object> arguments) {
        try {
            String argumentsJson = objectMapper.writeValueAsString(arguments);
            return callTool(toolName, argumentsJson);
        } catch (JsonProcessingException e) {
            log.error("ToolBridge: Failed to serialize arguments to JSON", e);
            return "Error: Failed to serialize arguments: " + e.getMessage();
        }
    }
    
    /**
     * 获取允许的工具列表
     * 
     * @return 工具名称列表
     */
    public List<String> getAllowedTools() {
        if (allowedTools != null) {
            return allowedTools;
        }
        // 如果没有限制，返回所有注册的工具
        return toolRegistry.getToolNames().stream().toList();
    }
}
