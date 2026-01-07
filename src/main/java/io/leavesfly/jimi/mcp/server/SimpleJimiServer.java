package io.leavesfly.jimi.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.core.JimiEngine;
import io.leavesfly.jimi.core.JimiFactory;
import io.leavesfly.jimi.core.session.Session;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.wire.message.ContentPartMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.Disposable;

/**
 * 极简 Jimi Server - Less is more
 * 通过 stdin/stdout 的 JSON 行协议提供服务，支持流式输出和元命令
 */
@Slf4j
@Component
public class SimpleJimiServer {
    
    private final JimiFactory jimiFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private JimiEngine engine;
    private BufferedWriter writer;
    
    public SimpleJimiServer(JimiFactory jimiFactory) {
        this.jimiFactory = jimiFactory;
    }
    
    public void start() {
        log.info("SimpleJimiServer starting...");
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            // 使用 FileDescriptor.out 直接写入原始 stdout，绕过 Spring Boot 的重定向
            writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8));
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                processRequest(line);
            }
        } catch (Exception e) {
            log.error("Server error", e);
        }
    }
    
    private void processRequest(String requestJson) {
        log.info("Received request: {}", requestJson);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(requestJson, Map.class);
            
            String input = (String) request.get("input");
            String workDir = (String) request.getOrDefault("workDir", System.getProperty("user.dir"));
            
            if (input == null || input.isBlank()) {
                writeJson(Map.of("error", "Missing 'input' field", "done", true));
                return;
            }
            
            ensureEngine(workDir);
            
            // 简化方案：直接执行并从 Context 提取结果
            AtomicReference<String> errorRef = new AtomicReference<>();
            
            engine.run(input)
                .doOnError(e -> {
                    log.error("Engine run error", e);
                    errorRef.set(e.getMessage());
                })
                .block(); // 同步等待完成
            
            // 从 Context 提取最后的 Assistant 响应
            String response = extractLastResponse();
            if (response != null && !response.isEmpty()) {
                writeJson(Map.of("chunk", response));
            }
            
            // 发送完成标记
            if (errorRef.get() != null) {
                writeJson(Map.of("error", errorRef.get(), "done", true));
            } else {
                writeJson(Map.of("done", true));
            }
            
        } catch (Exception e) {
            log.error("Request processing error", e);
            writeJson(Map.of("error", e.getMessage(), "done", true));
        }
    }
    
    /**
     * 从 Context 提取最后的 Assistant 响应
     */
    private String extractLastResponse() {
        if (engine == null) return null;
        
        var history = engine.getContext().getHistory();
        if (history.isEmpty()) return null;
        
        // 从后往前找最后一条 ASSISTANT 消息
        for (int i = history.size() - 1; i >= 0; i--) {
            var msg = history.get(i);
            if (msg.getRole() == io.leavesfly.jimi.llm.message.MessageRole.ASSISTANT) {
                return msg.getTextContent();
            }
        }
        return null;
    }
    
    private synchronized void writeJson(Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            writer.write(json);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.error("Write error", e);
        }
    }
    
    private void ensureEngine(String workDir) {
        if (engine != null) return;
        
        try {
            Path workPath = Paths.get(workDir);
            String sessionId = UUID.randomUUID().toString();
            Path sessionsDir = workPath.resolve(".jimi/sessions/" + sessionId);
            Files.createDirectories(sessionsDir);
            
            Session session = Session.builder()
                .id(sessionId)
                .workDir(workPath)
                .historyFile(sessionsDir.resolve("history.jsonl"))
                .build();
            
            engine = jimiFactory.createEngine()
                .session(session)
                .agentSpec(null)
                .model(null)
                .yolo(true)  // 自动批准所有操作，避免审批hang住
                .mcpConfigs(null)
                .build()
                .block();
                
            log.info("Engine initialized for workDir: {}", workDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create engine: " + e.getMessage(), e);
        }
    }
}
