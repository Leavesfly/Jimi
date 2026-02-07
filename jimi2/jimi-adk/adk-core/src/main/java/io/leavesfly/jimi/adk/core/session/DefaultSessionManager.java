package io.leavesfly.jimi.adk.core.session;

import io.leavesfly.jimi.adk.api.session.Session;
import io.leavesfly.jimi.adk.api.session.SessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 会话管理器默认实现
 */
@Slf4j
public class DefaultSessionManager implements SessionManager {
    
    /**
     * 会话存储目录
     */
    private final Path sessionsDirectory;
    
    /**
     * 对象映射器
     */
    private final ObjectMapper objectMapper;
    
    /**
     * 工作目录到会话的映射
     */
    private final Map<Path, Session> workDirSessions;
    
    public DefaultSessionManager(Path baseDir) {
        this.sessionsDirectory = baseDir.resolve(".jimi").resolve("sessions");
        this.objectMapper = new ObjectMapper(new YAMLFactory());
        this.workDirSessions = new HashMap<>();
        
        // 确保目录存在
        try {
            Files.createDirectories(sessionsDirectory);
        } catch (IOException e) {
            log.error("无法创建会话目录: {}", sessionsDirectory, e);
        }
    }
    
    @Override
    public Session createSession(Path workDir) {
        Session session = new DefaultSession(workDir);
        workDirSessions.put(workDir.toAbsolutePath().normalize(), session);
        saveSession(session);
        log.info("创建新会话: id={}, workDir={}", session.getId(), workDir);
        return session;
    }
    
    @Override
    public Optional<Session> loadSession(String sessionId) {
        Path sessionDir = sessionsDirectory.resolve(sessionId);
        Path metaFile = sessionDir.resolve("session.yaml");
        
        if (!Files.exists(metaFile)) {
            return Optional.empty();
        }
        
        try {
            Map<String, Object> meta = objectMapper.readValue(metaFile.toFile(), Map.class);
            String id = (String) meta.get("id");
            Path workDir = Path.of((String) meta.get("workDir"));
            Instant createdAt = Instant.parse((String) meta.get("createdAt"));
            Instant lastActivityAt = Instant.parse((String) meta.get("lastActivityAt"));
            
            Session session = new DefaultSession(id, workDir, createdAt, lastActivityAt);
            log.debug("加载会话: id={}", sessionId);
            return Optional.of(session);
        } catch (Exception e) {
            log.error("加载会话失败: {}", sessionId, e);
            return Optional.empty();
        }
    }
    
    @Override
    public Session getOrCreateSession(Path workDir) {
        Path normalizedPath = workDir.toAbsolutePath().normalize();
        
        // 检查缓存
        Session cached = workDirSessions.get(normalizedPath);
        if (cached != null) {
            return cached;
        }
        
        // 查找已有会话
        for (Session session : listSessions()) {
            if (session.getWorkDir().toAbsolutePath().normalize().equals(normalizedPath)) {
                workDirSessions.put(normalizedPath, session);
                return session;
            }
        }
        
        // 创建新会话
        return createSession(workDir);
    }
    
    @Override
    public void saveSession(Session session) {
        Path sessionDir = sessionsDirectory.resolve(session.getId());
        try {
            Files.createDirectories(sessionDir);
            
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("id", session.getId());
            meta.put("workDir", session.getWorkDir().toString());
            meta.put("createdAt", session.getCreatedAt().toString());
            meta.put("lastActivityAt", session.getLastActivityAt().toString());
            
            Path metaFile = sessionDir.resolve("session.yaml");
            objectMapper.writeValue(metaFile.toFile(), meta);
            log.debug("保存会话: id={}", session.getId());
        } catch (IOException e) {
            log.error("保存会话失败: {}", session.getId(), e);
        }
    }
    
    @Override
    public void deleteSession(String sessionId) {
        Path sessionDir = sessionsDirectory.resolve(sessionId);
        try {
            if (Files.exists(sessionDir)) {
                // 递归删除
                try (Stream<Path> walk = Files.walk(sessionDir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                log.warn("删除文件失败: {}", path);
                            }
                        });
                }
                log.info("删除会话: id={}", sessionId);
            }
        } catch (IOException e) {
            log.error("删除会话失败: {}", sessionId, e);
        }
    }
    
    @Override
    public List<Session> listSessions() {
        try {
            if (!Files.exists(sessionsDirectory)) {
                return Collections.emptyList();
            }
            
            try (Stream<Path> dirs = Files.list(sessionsDirectory)) {
                return dirs.filter(Files::isDirectory)
                        .map(dir -> loadSession(dir.getFileName().toString()))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            log.error("列出会话失败", e);
            return Collections.emptyList();
        }
    }
    
    @Override
    public Path getSessionsDirectory() {
        return sessionsDirectory;
    }
}
