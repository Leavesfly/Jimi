package io.leavesfly.jimi.mcp.server;

import io.leavesfly.jimi.core.JimiFactory;
import io.leavesfly.jimi.mcp.MCPSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * MCP工具注册表 - Facade模式
 * 将Jimi核心能力封装为MCP Tools供外部调用
 * 
 * 委托给以下组件：
 * - McpSessionManager: 会话管理
 * - McpJobExecutor: 任务执行
 * - McpToolDefinitionRegistry: 工具定义与注册
 */
@Slf4j
public class McpToolRegistry {
    
    private final McpSessionManager sessionManager;
    private final McpJobExecutor jobExecutor;
    private final McpToolDefinitionRegistry toolDefinitionRegistry;
    
    public McpToolRegistry(JimiFactory jimiFactory) {
        // 创建会话管理器
        this.sessionManager = new McpSessionManager(jimiFactory);
        
        // 创建任务执行器
        this.jobExecutor = new McpJobExecutor(sessionManager);
        
        // 创建工具定义注册表
        this.toolDefinitionRegistry = new McpToolDefinitionRegistry(sessionManager, jobExecutor);
        
        log.info("McpToolRegistry initialized with {} tools", toolDefinitionRegistry.getAllTools().size());
    }
    
    /**
     * 获取所有工具列表
     */
    public List<MCPSchema.Tool> getAllTools() {
        return toolDefinitionRegistry.getAllTools();
    }
    
    /**
     * 执行工具
     */
    public MCPSchema.CallToolResult executeTool(String toolName, Map<String, Object> arguments) {
        return toolDefinitionRegistry.executeTool(toolName, arguments);
    }
}
