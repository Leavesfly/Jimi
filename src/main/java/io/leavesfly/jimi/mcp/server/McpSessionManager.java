package io.leavesfly.jimi.mcp.server;

import io.leavesfly.jimi.core.JimiEngine;
import io.leavesfly.jimi.core.JimiFactory;
import io.leavesfly.jimi.core.session.Session;
import io.leavesfly.jimi.mcp.MCPSchema;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.leavesfly.jimi.mcp.server.McpResultHelper.errorResult;
import static io.leavesfly.jimi.mcp.server.McpResultHelper.textResult;
import static io.leavesfly.jimi.mcp.server.McpResultHelper.getOptionalArgument;

/**
 * MCP会话管理器
 * 管理会话的创建、获取、清理等操作
 */
@Slf4j
public class McpSessionManager {
    
    private final JimiFactory jimiFactory;
    private final Map<String, SessionContext> sessions;
    
    public McpSessionManager(JimiFactory jimiFactory) {
        this.jimiFactory = jimiFactory;
        this.sessions = new ConcurrentHashMap<>();
    }
    
    /**
     * 会话管理
     */
    public MCPSchema.CallToolResult manageSession(Map<String, Object> arguments) {
        try {
            String action = getOptionalArgument(arguments, "action", null);
            String sessionId = getOptionalArgument(arguments, "sessionId", null);
            String workDir = getOptionalArgument(arguments, "workDir", System.getProperty("user.dir"));
            String agentName = getOptionalArgument(arguments, "agent", "default");
            
            if (action == null) {
                return errorResult("Missing 'action'");
            }
            
            switch (action) {
                case "create" -> {
                    SessionContext ctx = createSessionContext(workDir, agentName);
                    sessions.put(ctx.getSessionId(), ctx);
                    return textResult("sessionId=" + ctx.getSessionId());
                }
                case "continue" -> {
                    if (sessionId == null || sessionId.isBlank()) {
                        return errorResult("Missing 'sessionId'");
                    }
                    if (!sessions.containsKey(sessionId)) {
                        return errorResult("Session not found: " + sessionId);
                    }
                    return textResult("sessionId=" + sessionId);
                }
                case "list" -> {
                    return textResult("sessions=" + String.join(",", sessions.keySet()));
                }
                default -> {
                    return errorResult("Unknown action: " + action);
                }
            }
        } catch (Exception e) {
            log.error("Error managing session", e);
            return errorResult("Error: " + e.getMessage());
        }
    }
    
    /**
     * 获取会话
     */
    public SessionContext getSession(String sessionId) {
        return sessions.get(sessionId);
    }
    
    /**
     * 存储会话
     */
    public void putSession(String sessionId, SessionContext ctx) {
        sessions.put(sessionId, ctx);
    }
    
    /**
     * 检查会话是否存在
     */
    public boolean containsSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }
    
    /**
     * 获取所有会话ID
     */
    public Set<String> getSessionIds() {
        return sessions.keySet();
    }
    
    /**
     * 创建会话上下文
     */
    public SessionContext createSessionContext(String workDir, String agentName) {
        String sessionId = UUID.randomUUID().toString();
        Path workPath = Paths.get(workDir);
        Path sessionsDir = workPath.resolve(".jimi/sessions/" + sessionId);
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            log.warn("Failed to create sessions dir: {}", sessionsDir, e);
        }
        
        Session session = Session.builder()
            .id(sessionId)
            .workDir(workPath)
            .historyFile(sessionsDir.resolve("history.jsonl"))
            .build();
        
        JimiEngine engine = jimiFactory.createEngine()
            .session(session)
            .agentSpec(agentName.equals("default") ? null :
                Paths.get("agents/" + agentName + "/agent.yaml"))
            .model(null)
            .yolo(false)
            .mcpConfigs(null)
            .build()
            .block();
        
        return new SessionContext(sessionId, session, engine);
    }
    
    /**
     * 创建引擎（不保存会话）
     */
    public JimiEngine createEngine(String workDir, String agentName) {
        SessionContext ctx = createSessionContext(workDir, agentName);
        return ctx.getEngine();
    }
    
    /**
     * 会话上下文
     */
    public static class SessionContext {
        private final String sessionId;
        private final Session session;
        private final JimiEngine engine;
        
        public SessionContext(String sessionId, Session session, JimiEngine engine) {
            this.sessionId = sessionId;
            this.session = session;
            this.engine = engine;
        }
        
        public String getSessionId() {
            return sessionId;
        }
        
        public Session getSession() {
            return session;
        }
        
        public JimiEngine getEngine() {
            return engine;
        }
    }
}
