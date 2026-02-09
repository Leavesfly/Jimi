package io.leavesfly.jimi.work.model;

import io.leavesfly.jimi.adk.api.engine.Engine;
import lombok.Getter;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 工作会话 - 封装 Engine 和会话元数据
 */
@Getter
public class WorkSession {

    private final String id;
    private final Engine engine;
    private final Path workDir;
    private final String agentName;
    private final LocalDateTime createdAt;

    /** 当前任务 ID */
    private volatile String currentJobId;
    /** 是否正在运行 */
    private volatile boolean running;

    public WorkSession(Engine engine, Path workDir, String agentName) {
        this(UUID.randomUUID().toString(), engine, workDir, agentName, LocalDateTime.now());
    }

    public WorkSession(String id, Engine engine, Path workDir, String agentName, LocalDateTime createdAt) {
        this.id = id;
        this.engine = engine;
        this.workDir = workDir;
        this.agentName = agentName;
        this.createdAt = createdAt;
    }

    /**
     * 开始任务
     */
    public void startJob(String jobId) {
        this.currentJobId = jobId;
        this.running = true;
    }

    /**
     * 结束任务
     */
    public void endJob() {
        this.currentJobId = null;
        this.running = false;
    }

    /**
     * 获取显示名称
     */
    public String getDisplayName() {
        String dirName = workDir.getFileName() != null ? workDir.getFileName().toString() : workDir.toString();
        return dirName + " (" + agentName + ")";
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
