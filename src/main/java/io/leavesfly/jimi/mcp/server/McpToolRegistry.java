package io.leavesfly.jimi.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.core.JimiEngine;
import io.leavesfly.jimi.core.JimiFactory;
import io.leavesfly.jimi.mcp.MCPSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * MCP工具注册表
 * 将Jimi核心能力封装为MCP Tools供外部调用
 */
@Slf4j
public class McpToolRegistry {
    
    private final JimiFactory jimiFactory;
    private final ObjectMapper objectMapper;
    private final Map<String, McpToolDefinition> tools;
    
    public McpToolRegistry(JimiFactory jimiFactory) {
        this.jimiFactory = jimiFactory;
        this.objectMapper = new ObjectMapper();
        this.tools = new LinkedHashMap<>();
        
        // 注册核心工具
        registerCoreTools();
    }
    
    /**
     * 注册核心工具
     */
    private void registerCoreTools() {
        // 工具1: jimi_execute - 执行Jimi任务
        registerTool(
            "jimi_execute",
            "Execute a Jimi task with natural language input",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "input", Map.of(
                        "type", "string",
                        "description", "The task description in natural language"
                    ),
                    "workDir", Map.of(
                        "type", "string",
                        "description", "Working directory path (optional)"
                    ),
                    "agent", Map.of(
                        "type", "string",
                        "description", "Agent name to use (default/code/architect, optional)"
                    )
                ),
                "required", List.of("input")
            ),
            this::executeJimiTask
        );
        
        log.info("Registered {} MCP tools", tools.size());
    }
    
    /**
     * 执行Jimi任务
     */
    private MCPSchema.CallToolResult executeJimiTask(Map<String, Object> arguments) {
        try {
            String input = (String) arguments.get("input");
            String workDir = (String) arguments.getOrDefault("workDir", System.getProperty("user.dir"));
            String agentName = (String) arguments.getOrDefault("agent", "default");
            
            log.info("Executing Jimi task: input={}, workDir={}, agent={}", input, workDir, agentName);
            
            // 创建会话和Engine
            java.nio.file.Path workPath = java.nio.file.Paths.get(workDir);
            
            // 创建会话目录
            String sessionId = java.util.UUID.randomUUID().toString();
            java.nio.file.Path sessionsDir = workPath.resolve(".jimi/sessions/" + sessionId);
            try {
                java.nio.file.Files.createDirectories(sessionsDir);
            } catch (Exception e) {
                log.warn("Failed to create sessions dir: {}", e.getMessage());
            }
            
            // 使用Builder创建Session
            io.leavesfly.jimi.core.session.Session session = io.leavesfly.jimi.core.session.Session.builder()
                .id(sessionId)
                .workDir(workPath)
                .historyFile(sessionsDir.resolve("history.jsonl"))
                .build();
            
            // 构建Engine
            JimiEngine engine = jimiFactory.createEngine()
                .session(session)
                .agentSpec(agentName.equals("default") ? null : 
                    java.nio.file.Paths.get("agents/" + agentName + "/agent.yaml"))
                .model(null)  // 使用默认模型
                .yolo(false)  // 不自动批准
                .mcpConfigs(null)
                .build()
                .block();
            
            if (engine == null) {
                throw new RuntimeException("Failed to create Jimi Engine");
            }
            
            // 收集Wire消息
            StringBuilder output = new StringBuilder();
            List<String> steps = new java.util.ArrayList<>();
            
            // 订阅Wire消息
            engine.getWire().asFlux()
                .doOnNext(msg -> {
                    String msgType = msg.getMessageType();
                    log.debug("Wire message: {}", msgType);
                    
                    // 记录关键步骤
                    if (msgType.equals("step_begin")) {
                        steps.add("Step started");
                    } else if (msgType.equals("content_part")) {
                        io.leavesfly.jimi.wire.message.ContentPartMessage cpm = 
                            (io.leavesfly.jimi.wire.message.ContentPartMessage) msg;
                        if (cpm.getContentPart() instanceof io.leavesfly.jimi.llm.message.TextPart) {
                            io.leavesfly.jimi.llm.message.TextPart tp = 
                                (io.leavesfly.jimi.llm.message.TextPart) cpm.getContentPart();
                            output.append(tp.getText());
                        }
                    } else if (msgType.equals("tool_call")) {
                        io.leavesfly.jimi.wire.message.ToolCallMessage tcm = 
                            (io.leavesfly.jimi.wire.message.ToolCallMessage) msg;
                        steps.add("Tool: " + tcm.getToolCall().getFunction().getName());
                    }
                })
                .subscribe();
            
            // 执行任务(同步等待完成)
            engine.run(input).block();
            
            // 构建结果
            String result = output.toString();
            if (result.isEmpty()) {
                result = "Task completed successfully. Steps: " + String.join(", ", steps);
            }
            
            log.info("Task completed with {} steps", steps.size());
            
            return MCPSchema.CallToolResult.builder()
                .content(List.of(
                    MCPSchema.TextContent.builder()
                        .type("text")
                        .text(result)
                        .build()
                ))
                .isError(false)
                .build();
                
        } catch (Exception e) {
            log.error("Error executing Jimi task", e);
            return MCPSchema.CallToolResult.builder()
                .content(List.of(
                    MCPSchema.TextContent.builder()
                        .type("text")
                        .text("Error: " + e.getMessage())
                        .build()
                ))
                .isError(true)
                .build();
        }
    }
    
    /**
     * 注册工具
     */
    private void registerTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        ToolExecutor executor
    ) {
        MCPSchema.Tool tool = MCPSchema.Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(inputSchema)
            .build();
        
        tools.put(name, new McpToolDefinition(tool, executor));
        log.debug("Registered tool: {}", name);
    }
    
    /**
     * 获取所有工具列表
     */
    public List<MCPSchema.Tool> getAllTools() {
        return tools.values().stream()
            .map(def -> def.tool)
            .toList();
    }
    
    /**
     * 执行工具
     */
    public MCPSchema.CallToolResult executeTool(String toolName, Map<String, Object> arguments) {
        McpToolDefinition def = tools.get(toolName);
        
        if (def == null) {
            log.warn("Tool not found: {}", toolName);
            return MCPSchema.CallToolResult.builder()
                .content(List.of(
                    MCPSchema.TextContent.builder()
                        .type("text")
                        .text("Tool not found: " + toolName)
                        .build()
                ))
                .isError(true)
                .build();
        }
        
        return def.executor.execute(arguments);
    }
    
    /**
     * 工具执行器接口
     */
    @FunctionalInterface
    private interface ToolExecutor {
        MCPSchema.CallToolResult execute(Map<String, Object> arguments);
    }
    
    /**
     * 工具定义
     */
    private record McpToolDefinition(
        MCPSchema.Tool tool,
        ToolExecutor executor
    ) {}
}
