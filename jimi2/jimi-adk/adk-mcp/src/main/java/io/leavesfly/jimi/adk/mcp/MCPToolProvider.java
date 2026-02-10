package io.leavesfly.jimi.adk.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.adk.api.agent.AgentSpec;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolProvider;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具提供者
 * 实现 ToolProvider 接口，在 Agent 初始化时自动加载 MCP 工具
 *
 * 根据工作目录下的 mcp.json 配置文件发现并加载 MCP 服务提供的工具
 */
@Slf4j
public class MCPToolProvider implements ToolProvider {

    private static final String MCP_CONFIG_FILE = "mcp.json";

    private MCPToolLoader toolLoader;

    @Override
    public boolean supports(AgentSpec agentSpec, Runtime runtime) {
        // 检查工作目录下是否有 mcp.json 配置文件
        Path configPath = runtime.getConfig().getWorkDir().resolve(MCP_CONFIG_FILE);
        return Files.exists(configPath);
    }

    @Override
    public List<Tool<?>> createTools(AgentSpec agentSpec, Runtime runtime) {
        List<Tool<?>> tools = new ArrayList<>();

        Path configPath = runtime.getConfig().getWorkDir().resolve(MCP_CONFIG_FILE);
        if (!Files.exists(configPath)) {
            return tools;
        }

        try {
            toolLoader = new MCPToolLoader(new ObjectMapper());
            String json = Files.readString(configPath);
            MCPConfig config = new ObjectMapper().readValue(json, MCPConfig.class);

            List<MCPTool> mcpTools = toolLoader.loadTools(config);
            tools.addAll(mcpTools);

            log.info("MCPToolProvider: 从 {} 加载了 {} 个 MCP 工具", configPath, mcpTools.size());
        } catch (Exception e) {
            log.error("MCPToolProvider: 加载 MCP 工具失败: {}", e.getMessage(), e);
        }

        return tools;
    }

    @Override
    public int getOrder() {
        return 200;  // MCP 工具加载优先级较低，在核心和扩展工具之后
    }

    @Override
    public String getName() {
        return "MCPToolProvider";
    }

    /**
     * 关闭所有 MCP 客户端连接
     */
    public void close() {
        if (toolLoader != null) {
            toolLoader.closeAll();
        }
    }
}
