package io.leavesfly.jimi.adk.core.session;

import io.leavesfly.jimi.adk.api.session.Session;
import lombok.Getter;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * 会话默认实现
 */
@Getter
public class DefaultSession implements Session {
    
    /**
     * 会话 ID
     */
    private final String id;
    
    /**
     * 工作目录
     */
    private final Path workDir;
    
    /**
     * 创建时间
     */
    private final Instant createdAt;
    
    /**
     * 最后活动时间
     */
    private Instant lastActivityAt;
    
    /**
     * 创建新会话
     */
    public DefaultSession(Path workDir) {
        this.id = UUID.randomUUID().toString();
        this.workDir = workDir;
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
    }
    
    /**
     * 使用指定 ID 创建会话（用于加载已有会话）
     */
    public DefaultSession(String id, Path workDir, Instant createdAt, Instant lastActivityAt) {
        this.id = id;
        this.workDir = workDir;
        this.createdAt = createdAt;
        this.lastActivityAt = lastActivityAt;
    }
    
    @Override
    public void touch() {
        this.lastActivityAt = Instant.now();
    }
    
    @Override
    public boolean isValid() {
        return id != null && workDir != null;
    }
}
