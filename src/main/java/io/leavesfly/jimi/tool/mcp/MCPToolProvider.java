package io.leavesfly.jimi.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.agent.AgentSpec;
import io.leavesfly.jimi.soul.runtime.Runtime;
import io.leavesfly.jimi.tool.Tool;
import io.leavesfly.jimi.tool.ToolProvider;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.mcp.MCPTool;
import io.leavesfly.jimi.tool.mcp.MCPToolLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具提供者
 * 
 * 职责：
 * - 检测是否配置了 MCP 配置文件
 * - 加载 MCP 工具
 * 
 * 注意：
 * - MCP 配置文件由 JimiFactory 通过参数传入
 * - 此提供者需要外部设置 MCP 配置文件列表
 */
@Slf4j
@Component
public class MCPToolProvider implements ToolProvider {
    
    private final MCPToolLoader mcpToolLoader;
    private final ObjectMapper objectMapper;
    
    // MCP 配置文件列表（由外部设置）
    private List<Path> mcpConfigFiles;
    
    @Autowired
    public MCPToolProvider(MCPToolLoader mcpToolLoader, ObjectMapper objectMapper) {
        this.mcpToolLoader = mcpToolLoader;
        this.objectMapper = objectMapper;
    }
    
    /**
     * 设置 MCP 配置文件列表
     * 由 JimiFactory 在创建工具前调用
     */
    public void setMcpConfigFiles(List<Path> mcpConfigFiles) {
        this.mcpConfigFiles = mcpConfigFiles;
    }
    
    @Override
    public boolean supports(AgentSpec agentSpec, Runtime runtime) {
        // 检查是否有 MCP 配置文件
        return mcpConfigFiles != null && !mcpConfigFiles.isEmpty();
    }
    
    @Override
    public List<Tool<?>> createTools(AgentSpec agentSpec, Runtime runtime) {
        List<Tool<?>> allMcpTools = new ArrayList<>();
        
        // 临时创建一个空的注册表用于加载工具
        ToolRegistry tempRegistry = new ToolRegistry(objectMapper);
        
        for (Path configFile : mcpConfigFiles) {
            try {
                List<MCPTool> mcpTools = mcpToolLoader.loadFromFile(configFile, tempRegistry);
                allMcpTools.addAll(mcpTools);
                log.info("Loaded {} MCP tools from {}", mcpTools.size(), configFile);
            } catch (Exception e) {
                log.error("Failed to load MCP config: {}", configFile, e);
                // 继续加载其他配置文件
            }
        }
        
        return allMcpTools;
    }
    
    @Override
    public int getOrder() {
        return 60;  // 在 Task 之后加载
    }
}
