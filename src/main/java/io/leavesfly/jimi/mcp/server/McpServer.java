package io.leavesfly.jimi.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.core.JimiEngine;
import io.leavesfly.jimi.core.JimiFactory;
import io.leavesfly.jimi.core.session.Session;
import io.leavesfly.jimi.mcp.JsonRpcMessage;
import io.leavesfly.jimi.mcp.MCPSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Server实现
 * 通过StdIO提供标准MCP协议服务,使Jimi能被IDEA插件等MCP Client调用
 * 
 * 核心功能:
 * 1. JSON-RPC 2.0协议处理
 * 2. StdIO双向通信
 * 3. 多会话管理
 * 4. 工具暴露与执行
 */
@Slf4j
@Component
public class McpServer implements AutoCloseable {
    
    private final JimiFactory jimiFactory;
    private final ObjectMapper objectMapper;
    private final Map<String, SessionContext> sessions;
    private final McpToolRegistry toolRegistry;
    
    private BufferedReader reader;
    private BufferedWriter writer;
    private volatile boolean running;
    
    public McpServer(JimiFactory jimiFactory) {
        this.jimiFactory = jimiFactory;
        this.objectMapper = new ObjectMapper();
        this.sessions = new ConcurrentHashMap<>();
        this.toolRegistry = new McpToolRegistry(jimiFactory);
    }
    
    /**
     * 启动MCP Server(StdIO模式)
     */
    public void start() {
        log.info("Starting MCP Server in StdIO mode...");
        
        this.reader = new BufferedReader(new InputStreamReader(System.in));
        this.writer = new BufferedWriter(new OutputStreamWriter(System.out));
        this.running = true;
        
        try {
            messageLoop();
        } catch (Exception e) {
            log.error("MCP Server error", e);
        } finally {
            cleanup();
        }
    }
    
    /**
     * 消息循环 - 持续读取并处理JSON-RPC请求
     */
    private void messageLoop() throws IOException {
        String line;
        while (running && (line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            
            log.debug("Received request: {}", line);
            
            try {
                JsonRpcMessage.Request request = objectMapper.readValue(
                    line, 
                    JsonRpcMessage.Request.class
                );
                
                JsonRpcMessage.Response response = handleRequest(request);
                sendResponse(response);
                
            } catch (Exception e) {
                log.error("Error processing request", e);
                sendError(null, -32700, "Parse error: " + e.getMessage());
            }
        }
    }
    
    /**
     * 处理JSON-RPC请求
     */
    private JsonRpcMessage.Response handleRequest(JsonRpcMessage.Request request) {
        String method = request.getMethod();
        Object requestId = request.getId();
        
        try {
            Map<String, Object> result = switch (method) {
                case "initialize" -> handleInitialize(request.getParams());
                case "tools/list" -> handleListTools(request.getParams());
                case "tools/call" -> handleCallTool(request.getParams());
                default -> throw new UnsupportedOperationException("Unknown method: " + method);
            };
            
            return JsonRpcMessage.Response.builder()
                .jsonrpc("2.0")
                .id(requestId)
                .result(result)
                .build();
                
        } catch (Exception e) {
            log.error("Error handling method: {}", method, e);
            return JsonRpcMessage.Response.builder()
                .jsonrpc("2.0")
                .id(requestId)
                .error(JsonRpcMessage.Error.builder()
                    .code(-32603)
                    .message("Internal error: " + e.getMessage())
                    .build())
                .build();
        }
    }
    
    /**
     * 处理initialize请求
     */
    private Map<String, Object> handleInitialize(Map<String, Object> params) {
        log.info("Initialize request received");
        
        MCPSchema.InitializeResult result = MCPSchema.InitializeResult.builder()
            .protocolVersion("2024-11-05")
            .capabilities(Map.of(
                "tools", Map.of("listChanged", false)
            ))
            .serverInfo(Map.of(
                "name", "jimi",
                "version", "0.1.0"
            ))
            .build();
        
        return objectMapper.convertValue(result, Map.class);
    }
    
    /**
     * 处理tools/list请求
     */
    private Map<String, Object> handleListTools(Map<String, Object> params) {
        log.debug("List tools request");
        
        List<MCPSchema.Tool> tools = toolRegistry.getAllTools();
        
        MCPSchema.ListToolsResult result = MCPSchema.ListToolsResult.builder()
            .tools(tools)
            .build();
        
        return objectMapper.convertValue(result, Map.class);
    }
    
    /**
     * 处理tools/call请求
     */
    private Map<String, Object> handleCallTool(Map<String, Object> params) {
        String toolName = (String) params.get("name");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
        
        log.info("Calling tool: {} with args: {}", toolName, arguments);
        
        MCPSchema.CallToolResult result = toolRegistry.executeTool(
            toolName, 
            arguments != null ? arguments : Map.of()
        );
        
        return objectMapper.convertValue(result, Map.class);
    }
    
    /**
     * 发送响应到StdIO
     */
    private void sendResponse(JsonRpcMessage.Response response) throws IOException {
        String json = objectMapper.writeValueAsString(response);
        log.debug("Sending response: {}", json);
        
        writer.write(json);
        writer.write("\n");
        writer.flush();
    }
    
    /**
     * 发送错误响应
     */
    private void sendError(Object requestId, int code, String message) {
        try {
            JsonRpcMessage.Response response = JsonRpcMessage.Response.builder()
                .jsonrpc("2.0")
                .id(requestId)
                .error(JsonRpcMessage.Error.builder()
                    .code(code)
                    .message(message)
                    .build())
                .build();
            
            sendResponse(response);
        } catch (IOException e) {
            log.error("Failed to send error response", e);
        }
    }
    
    /**
     * 清理资源
     */
    private void cleanup() {
        log.info("Cleaning up MCP Server...");
        running = false;
        
        // 关闭所有会话
        sessions.values().forEach(ctx -> {
            try {
                if (ctx.engine != null) {
                    // JimiEngine cleanup if needed
                }
            } catch (Exception e) {
                log.warn("Error closing session", e);
            }
        });
        sessions.clear();
        
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
        } catch (IOException e) {
            log.error("Error closing streams", e);
        }
    }
    
    @Override
    public void close() {
        cleanup();
    }
    
    /**
     * 会话上下文
     */
    private static class SessionContext {
        String sessionId;
        Session session;
        JimiEngine engine;
        
        SessionContext(String sessionId, Session session, JimiEngine engine) {
            this.sessionId = sessionId;
            this.session = session;
            this.engine = engine;
        }
    }
}
