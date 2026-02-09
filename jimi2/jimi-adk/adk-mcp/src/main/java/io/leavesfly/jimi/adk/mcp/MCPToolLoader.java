package io.leavesfly.jimi.adk.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具加载器
 * 负责加载和管理 MCP 工具的生命周期
 *
 * 主要职责：
 * 1. 从配置文件加载 MCP 服务配置
 * 2. 为每个服务创建 JsonRpcClient 客户端
 * 3. 查询服务提供的工具列表
 * 4. 将工具包装为 MCPTool 并注册到 ToolRegistry
 * 5. 统一管理客户端生命周期
 */
@Slf4j
public class MCPToolLoader {

    private final ObjectMapper objectMapper;
    private final List<JsonRpcClient> activeClients = new ArrayList<>();

    public MCPToolLoader() {
        this.objectMapper = new ObjectMapper();
    }

    public MCPToolLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从文件加载 MCP 工具
     *
     * @param configPath   配置文件路径
     * @param toolRegistry 工具注册表
     * @return 加载的工具列表
     * @throws IOException 文件读取失败时抛出
     */
    public List<MCPTool> loadFromFile(Path configPath, ToolRegistry toolRegistry) throws IOException {
        String json = Files.readString(configPath);
        MCPConfig config = objectMapper.readValue(json, MCPConfig.class);
        return loadFromConfig(config, toolRegistry);
    }

    /**
     * 从配置对象加载 MCP 工具
     *
     * @param config       MCP 配置对象
     * @param toolRegistry 工具注册表
     * @return 加载的工具列表
     */
    public List<MCPTool> loadFromConfig(MCPConfig config, ToolRegistry toolRegistry) {
        List<MCPTool> loadedTools = new ArrayList<>();
        if (config.getMcpServers() == null || config.getMcpServers().isEmpty()) {
            return loadedTools;
        }

        for (Map.Entry<String, MCPConfig.ServerConfig> entry : config.getMcpServers().entrySet()) {
            String serverName = entry.getKey();
            MCPConfig.ServerConfig serverConfig = entry.getValue();
            try {
                JsonRpcClient client = createClient(serverName, serverConfig);
                activeClients.add(client);

                client.initialize();

                MCPSchema.ListToolsResult toolsResult = client.listTools();
                List<MCPSchema.Tool> tools = toolsResult.getTools();

                for (MCPSchema.Tool tool : tools) {
                    MCPTool mcpTool = new MCPTool(tool, client);
                    toolRegistry.register(mcpTool);
                    loadedTools.add(mcpTool);
                    log.info("Loaded MCP tool: {} from server: {}", tool.getName(), serverName);
                }
            } catch (Exception e) {
                log.error("Failed to load MCP tools from server {}: {}", serverName, e.getMessage());
            }
        }
        return loadedTools;
    }

    /**
     * 从配置加载 MCP 工具（不注册到 Registry，直接返回列表）
     *
     * @param config MCP 配置对象
     * @return 加载的工具列表
     */
    public List<MCPTool> loadTools(MCPConfig config) {
        List<MCPTool> loadedTools = new ArrayList<>();
        if (config.getMcpServers() == null || config.getMcpServers().isEmpty()) {
            return loadedTools;
        }

        for (Map.Entry<String, MCPConfig.ServerConfig> entry : config.getMcpServers().entrySet()) {
            String serverName = entry.getKey();
            MCPConfig.ServerConfig serverConfig = entry.getValue();
            try {
                JsonRpcClient client = createClient(serverName, serverConfig);
                activeClients.add(client);

                client.initialize();

                MCPSchema.ListToolsResult toolsResult = client.listTools();
                List<MCPSchema.Tool> tools = toolsResult.getTools();

                for (MCPSchema.Tool tool : tools) {
                    MCPTool mcpTool = new MCPTool(tool, client);
                    loadedTools.add(mcpTool);
                    log.info("Loaded MCP tool: {} from server: {}", tool.getName(), serverName);
                }
            } catch (Exception e) {
                log.error("Failed to load MCP tools from server {}: {}", serverName, e.getMessage());
            }
        }
        return loadedTools;
    }

    /**
     * 创建客户端实例
     */
    private JsonRpcClient createClient(String serverName, MCPConfig.ServerConfig config) throws IOException {
        if (config.isStdio()) {
            log.info("Creating STDIO MCP client for server: {}", serverName);
            return new StdIoJsonRpcClient(config.getCommand(), config.getArgs(), config.getEnv());
        } else if (config.isHttp()) {
            log.info("Creating HTTP MCP client for server: {} at URL: {}", serverName, config.getUrl());
            return new HttpJsonRpcClient(config.getUrl(), config.getHeaders());
        } else {
            throw new IllegalArgumentException("Invalid MCP server config for: " + serverName);
        }
    }

    /**
     * 关闭所有活跃的客户端连接
     */
    public void closeAll() {
        log.info("Closing {} MCP client(s)...", activeClients.size());
        for (JsonRpcClient client : activeClients) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Failed to close MCP client: {}", e.getMessage());
            }
        }
        activeClients.clear();
        log.info("All MCP clients closed");
    }
}
