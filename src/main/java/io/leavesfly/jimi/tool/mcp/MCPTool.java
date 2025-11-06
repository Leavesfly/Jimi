package io.leavesfly.jimi.tool.mcp;

import io.leavesfly.jimi.mcp.JsonRpcClient;
import io.leavesfly.jimi.mcp.MCPResultConverter;
import io.leavesfly.jimi.mcp.MCPSchema;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 工具包装器 - 轻量级本地实现
 * 不依赖io.modelcontextprotocol.sdk，直接使用本地客户端
 * 
 * 将外部MCP服务提供的工具包装成Jimi的AbstractTool，使其能被统一调用
 */
@Slf4j
public class MCPTool extends AbstractTool<Map<String, Object>> {
    /** MCP客户端，用于与外部服务通信 */
    private final JsonRpcClient mcpClient;
    /** 工具名称 */
    private final String mcpToolName;
    /** 执行超时时间（秒） */
    private final int timeoutSeconds;

    /**
     * 构造MCP工具（默认超时20秒）
     * 
     * @param mcpTool MCP工具定义
     * @param mcpClient MCP客户端
     */
    public MCPTool(MCPSchema.Tool mcpTool, JsonRpcClient mcpClient) {
        this(mcpTool, mcpClient, 20);
    }

    /**
     * 构造MCP工具（自定义超时）
     * 
     * @param mcpTool MCP工具定义
     * @param mcpClient MCP客户端
     * @param timeoutSeconds 超时时间（秒）
     */
    public MCPTool(MCPSchema.Tool mcpTool, JsonRpcClient mcpClient, int timeoutSeconds) {
        super(
            mcpTool.getName(),
            mcpTool.getDescription() != null ? mcpTool.getDescription() : "",
            createParamsClass()
        );
        this.mcpClient = mcpClient;
        this.mcpToolName = mcpTool.getName();
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 执行MCP工具
     * 调用外部MCP服务的工具，并转换结果
     * 
     * @param params 工具参数
     * @return 异步的ToolResult
     */
    @Override
    public Mono<ToolResult> execute(Map<String, Object> params) {
        return Mono.fromCallable(() -> {
            try {
                // 调用MCP服务的工具
                MCPSchema.CallToolResult result = mcpClient.callTool(
                    mcpToolName,
                    params != null ? params : new HashMap<>()
                );
                // 转换为Jimi的ToolResult格式
                return MCPResultConverter.convert(result);
            } catch (Exception e) {
                log.error("Failed to execute MCP tool {}: {}", mcpToolName, e.getMessage());
                return ToolResult.error(
                    "Failed to execute MCP tool: " + e.getMessage(),
                    "MCP tool execution failed"
                );
            }
        });
    }

    /**
     * 创建参数类型
     * 返回Map<String, Object>类型，用于接收任意JSON对象参数
     */
    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> createParamsClass() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }
}
