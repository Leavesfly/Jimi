package io.leavesfly.jimi.adk.mcp;

import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 工具包装器
 * 将外部 MCP 服务提供的工具包装为 jimi2 的 Tool 接口实现
 *
 * 参数类型为 Map&lt;String, Object&gt;，接收任意 JSON 对象参数
 */
@Slf4j
public class MCPTool implements Tool<Map<String, Object>> {

    private final String name;
    private final String description;
    private final JsonRpcClient mcpClient;
    private final String mcpToolName;

    /**
     * 构造 MCP 工具
     *
     * @param mcpTool   MCP 工具定义
     * @param mcpClient MCP 客户端
     */
    public MCPTool(MCPSchema.Tool mcpTool, JsonRpcClient mcpClient) {
        this.name = mcpTool.getName();
        this.description = mcpTool.getDescription() != null ? mcpTool.getDescription() : "";
        this.mcpClient = mcpClient;
        this.mcpToolName = mcpTool.getName();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<Map<String, Object>> getParamsType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> params) {
        return Mono.fromCallable(() -> {
            try {
                MCPSchema.CallToolResult result = mcpClient.callTool(
                        mcpToolName,
                        params != null ? params : new HashMap<>()
                );
                return MCPResultConverter.convert(result);
            } catch (Exception e) {
                log.error("Failed to execute MCP tool {}: {}", mcpToolName, e.getMessage());
                return ToolResult.error("Failed to execute MCP tool: " + e.getMessage());
            }
        });
    }
}
