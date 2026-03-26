package io.leavesfly.jimi.mcp.server;

import io.leavesfly.jimi.mcp.MCPSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP工具定义注册表
 * 管理工具的定义、注册和查找
 */
@Slf4j
public class McpToolDefinitionRegistry {
    
    private final Map<String, McpToolDefinition> tools;
    private final McpSessionManager sessionManager;
    private final McpJobExecutor jobExecutor;
    
    public McpToolDefinitionRegistry(McpSessionManager sessionManager, McpJobExecutor jobExecutor) {
        this.tools = new LinkedHashMap<>();
        this.sessionManager = sessionManager;
        this.jobExecutor = jobExecutor;
        registerCoreTools();
    }
    
    private void registerCoreTools() {
        // 工具0: jimi_session - 会话管理
        registerTool("jimi_session", "Manage Jimi sessions (create/continue/list)",
            Map.of("type", "object", "properties", Map.of(
                "action", Map.of("type", "string", "enum", List.of("create", "continue", "list"), "description", "Session action"),
                "sessionId", Map.of("type", "string", "description", "Session ID (for continue)"),
                "workDir", Map.of("type", "string", "description", "Working directory path (optional)"),
                "agent", Map.of("type", "string", "description", "Agent name to use (optional)")
            ), "required", List.of("action")), sessionManager::manageSession);
        
        // 工具1: jimi_execute - 执行Jimi任务
        registerTool("jimi_execute", "Execute a Jimi task with natural language input",
            Map.of("type", "object", "properties", Map.of(
                "input", Map.of("type", "string", "description", "The task description in natural language"),
                "sessionId", Map.of("type", "string", "description", "Session ID for continuous conversation (optional)"),
                "workDir", Map.of("type", "string", "description", "Working directory path (optional)"),
                "agent", Map.of("type", "string", "description", "Agent name to use (default/code/architect, optional)")
            ), "required", List.of("input")), jobExecutor::executeJimiTask);
        
        // 工具2: jimi_execute_stream - 流式执行
        registerTool("jimi_execute_stream", "Execute a Jimi task with streaming output",
            Map.of("type", "object", "properties", Map.of(
                "input", Map.of("type", "string", "description", "The task description in natural language"),
                "sessionId", Map.of("type", "string", "description", "Session ID for continuous conversation (optional)"),
                "workDir", Map.of("type", "string", "description", "Working directory path (optional)"),
                "agent", Map.of("type", "string", "description", "Agent name to use (default/code/architect, optional)")
            ), "required", List.of("input")), jobExecutor::executeJimiTaskStream);
        
        // 工具3: jimi_get_output - 获取流式输出
        registerTool("jimi_get_output", "Get streaming output for a running job",
            Map.of("type", "object", "properties", Map.of(
                "jobId", Map.of("type", "string", "description", "Job ID returned by jimi_execute_stream"),
                "since", Map.of("type", "integer", "description", "Output index to read from")
            ), "required", List.of("jobId")), jobExecutor::getJobOutput);
        
        // 工具4: jimi_approval - 审批处理
        registerTool("jimi_approval", "Handle approval requests",
            Map.of("type", "object", "properties", Map.of(
                "action", Map.of("type", "string", "enum", List.of("list", "approve", "approve_session", "reject"), "description", "Approval action"),
                "toolCallId", Map.of("type", "string", "description", "Tool call ID for approval"),
                "sessionId", Map.of("type", "string", "description", "Filter approvals by session ID (optional)")
            ), "required", List.of("action")), jobExecutor::handleApproval);
        
        // 工具5: jimi_cancel - 取消任务
        registerTool("jimi_cancel", "Cancel a running job",
            Map.of("type", "object", "properties", Map.of(
                "jobId", Map.of("type", "string", "description", "Job ID to cancel")
            ), "required", List.of("jobId")), jobExecutor::cancelJob);
        
        log.info("Registered {} MCP tools", tools.size());
    }
    
    private void registerTool(String name, String description, Map<String, Object> inputSchema, ToolExecutor executor) {
        MCPSchema.Tool tool = MCPSchema.Tool.builder()
            .name(name).description(description).inputSchema(inputSchema).build();
        tools.put(name, new McpToolDefinition(tool, executor));
        log.debug("Registered tool: {}", name);
    }
    
    public List<MCPSchema.Tool> getAllTools() {
        return tools.values().stream().map(def -> def.tool()).toList();
    }
    
    public MCPSchema.CallToolResult executeTool(String toolName, Map<String, Object> arguments) {
        McpToolDefinition def = tools.get(toolName);
        if (def == null) {
            log.warn("Tool not found: {}", toolName);
            return MCPSchema.CallToolResult.builder()
                .content(List.of(MCPSchema.TextContent.builder().type("text").text("Tool not found: " + toolName).build()))
                .isError(true).build();
        }
        return def.executor().execute(arguments);
    }
    
    @FunctionalInterface
    public interface ToolExecutor {
        MCPSchema.CallToolResult execute(Map<String, Object> arguments);
    }
    
    private record McpToolDefinition(MCPSchema.Tool tool, ToolExecutor executor) {}
}
