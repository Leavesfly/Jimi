package io.leavesfly.jimi.mcp.server;

import io.leavesfly.jimi.mcp.MCPSchema;

import java.util.List;
import java.util.Map;

/**
 * MCP结果构建辅助类
 * 提供统一的结果构建方法和参数处理方法
 */
public final class McpResultHelper {
    
    private McpResultHelper() {
        // 工具类，禁止实例化
    }
    
    /**
     * 构建文本结果
     */
    public static MCPSchema.CallToolResult textResult(String text) {
        return MCPSchema.CallToolResult.builder()
            .content(List.of(
                MCPSchema.TextContent.builder()
                    .type("text")
                    .text(text)
                    .build()
            ))
            .isError(false)
            .build();
    }
    
    /**
     * 构建错误结果
     */
    public static MCPSchema.CallToolResult errorResult(String text) {
        return MCPSchema.CallToolResult.builder()
            .content(List.of(
                MCPSchema.TextContent.builder()
                    .type("text")
                    .text(text)
                    .build()
            ))
            .isError(true)
            .build();
    }
    
    /**
     * 从参数Map中获取必需的字符串参数
     * 
     * @param arguments 参数Map
     * @param key 参数键名
     * @return 参数值
     * @throws IllegalArgumentException 如果参数不存在或为空
     */
    public static String getRequiredArgument(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        String strValue = String.valueOf(value);
        if (strValue.isBlank()) {
            throw new IllegalArgumentException("Required argument is blank: " + key);
        }
        return strValue;
    }
    
    /**
     * 从参数Map中获取可选的字符串参数
     * 
     * @param arguments 参数Map
     * @param key 参数键名
     * @param defaultValue 默认值
     * @return 参数值，如果不存在则返回默认值
     */
    public static String getOptionalArgument(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        String strValue = String.valueOf(value);
        return strValue.isBlank() ? defaultValue : strValue;
    }
}
