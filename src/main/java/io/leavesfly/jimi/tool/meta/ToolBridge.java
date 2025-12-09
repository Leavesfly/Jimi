package io.leavesfly.jimi.tool.meta;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * @return 工具执行结果：成功时返回 output 内容，失败时返回 "Error: <message>"
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
            // 调用 ToolRegistry 并 block 等待结果
            ToolResult result = toolRegistry.execute(toolName, arguments)
                    .block(Duration.ofSeconds(60)); // 单个工具调用最多等待 60 秒
            
            if (result == null) {
                return "Error: Tool execution returned null";
            }
            
            // 简化返回：成功时返回 output，失败时返回 Error 前缀
            if (result.isOk()) {
                String output = result.getOutput();
                if (logExecutionDetails) {
                    log.info("ToolBridge: Tool '{}' executed successfully, output length: {} chars", 
                            toolName, output.length());
                }
                return output;
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
     * 调用工具（Map 参数）
     * 
     * @param toolName 工具名称
     * @param arguments 参数 Map
     * @return 工具执行结果：成功时返回 output 内容，失败时返回 "Error: <message>"
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
