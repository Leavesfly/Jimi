package io.leavesfly.jimi.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.mcp.*;
import io.leavesfly.jimi.tool.ToolRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具加载器 - Spring Service
 * 负责加载和管理 MCP 工具的生命周期
 * 
 * 主要职责：
 * 1. 从配置文件加载MCP服务配置
 * 2. 为每个服务创建StdIoJsonRpcClient客户端
 * 3. 查询服务提供的工具列表
 * 4. 将工具包装为MCPTool并注册到ToolRegistry
 * 5. 统一管理客户端生命周期（通过 @PreDestroy 自动清理）
 * 
 * @author Jimi Team
 */
@Slf4j
@Service
public class MCPToolLoader {
    /** JSON序列化工具 */
    private final ObjectMapper objectMapper;
    /** 活跃的客户端列表，用于统一管理和关闭 */
    private final List<JsonRpcClient> activeClients = new ArrayList<>();

    @Autowired
    public MCPToolLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        log.info("MCPToolLoader initialized as Spring Service");
    }

    /**
     * 从文件加载MCP工具
     * 
     * @param configPath 配置文件路径
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
     * 从配置对象加载MCP工具
     * 核心加载逻辑：遍历每个服务配置，创建客户端，查询工具，注册到ToolRegistry
     * 
     * @param config MCP配置对象
     * @param toolRegistry 工具注册表
     * @return 加载的工具列表
     */
    public List<MCPTool> loadFromConfig(MCPConfig config, ToolRegistry toolRegistry) {
        List<MCPTool> loadedTools = new ArrayList<>();
        if (config.getMcpServers() == null || config.getMcpServers().isEmpty()) {
            return loadedTools;
        }
        // 遍历每个配置的MCP服务
        for (Map.Entry<String, MCPConfig.ServerConfig> entry : config.getMcpServers().entrySet()) {
            String serverName = entry.getKey();
            MCPConfig.ServerConfig serverConfig = entry.getValue();
            try {
                // 1. 创建客户端连接
                JsonRpcClient client = createClient(serverName, serverConfig);
                activeClients.add(client);
                // 2. 初始化连接
                client.initialize();
                // 3. 获取工具列表
                MCPSchema.ListToolsResult toolsResult = client.listTools();
                List<MCPSchema.Tool> tools = toolsResult.getTools();
                // 4. 包装和注册每个工具
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
     * 创建客户端实例
     * 根据配置类型（STDIO或HTTP）创建对应的客户端
     * 
     * @param serverName 服务名称
     * @param config 服务配置
     * @return 客户端实例
     * @throws IOException 创建失败时抛出
     */
    private JsonRpcClient createClient(String serverName, MCPConfig.ServerConfig config) throws IOException {
        if (config.isStdio()) {
            return createStdioClient(serverName, config);
        } else if (config.isHttp()) {
            return createHttpClient(serverName, config);
        } else {
            throw new IllegalArgumentException("Invalid MCP server config");
        }
    }

    /**
     * 创建STDIO客户端
     * 通过命令行启动外部MCP服务进程
     * 
     * @param serverName 服务名称（用于日志）
     * @param config 服务配置
     * @return STDIO JSON-RPC客户端
     * @throws IOException 进程启动失败时抛出
     */
    private StdIoJsonRpcClient createStdioClient(String serverName, MCPConfig.ServerConfig config) throws IOException {
        return new StdIoJsonRpcClient(
            config.getCommand(),
            config.getArgs(),
            config.getEnv()
        );
    }

    /**
     * 创建HTTP客户端
     * 通过HTTP协议连接远程MCP服务
     * 
     * @param serverName 服务名称（用于日志）
     * @param config 服务配置
     * @return HTTP JSON-RPC客户端
     */
    private JsonRpcClient createHttpClient(String serverName, MCPConfig.ServerConfig config) {
        log.info("Creating HTTP MCP client for server: {} at URL: {}", serverName, config.getUrl());
        return new HttpJsonRpcClient(
            config.getUrl(),
            config.getHeaders()
        );
    }

    /**
     * 关闭所有活跃的客户端连接
     * 由 Spring 容器在应用关闭时自动调用
     */
    @PreDestroy
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
