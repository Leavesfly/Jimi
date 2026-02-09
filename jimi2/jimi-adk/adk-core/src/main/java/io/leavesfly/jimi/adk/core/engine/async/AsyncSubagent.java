package io.leavesfly.jimi.adk.core.engine.async;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import lombok.Builder;
import lombok.Data;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * 异步子代理实体
 * 封装异步运行的子代理的所有状态信息
 */
@Data
@Builder
public class AsyncSubagent {
    
    /** 唯一标识（短 UUID） */
    private String id;
    
    /** 显示名称（通常是 Agent 名称） */
    private String name;
    
    /** 运行模式 */
    private AsyncSubagentMode mode;
    
    /** 当前状态 */
    private AsyncSubagentStatus status;
    
    /** 底层 Agent 配置 */
    private Agent agent;
    
    /** 独立上下文 */
    private Context context;
    
    /** 原始任务提示 */
    private String prompt;
    
    /** 启动时间 */
    private Instant startTime;
    
    /** 结束时间（完成/失败/取消时设置） */
    private Instant endTime;
    
    /** Reactor 订阅（用于取消执行） */
    private Disposable subscription;
    
    /** 执行结果（成功完成时设置） */
    private String result;
    
    /** 错误信息（失败时设置） */
    private Throwable error;
    
    /** 完成回调 */
    private AsyncSubagentCallback callback;
    
    /** 超时时间（可选） */
    private Duration timeout;
    
    /** 监控模式的触发正则（WATCH 模式使用） */
    private String triggerPattern;
    
    /** 工作目录（用于持久化） */
    private Path workDir;
    
    /**
     * 获取运行时长
     */
    public Duration getRunningDuration() {
        Instant end = endTime != null ? endTime : Instant.now();
        return Duration.between(startTime, end);
    }
    
    /**
     * 是否已完成（包括成功、失败、取消、超时）
     */
    public boolean isCompleted() {
        return status != null && status.isTerminal();
    }
    
    /**
     * 是否成功完成
     */
    public boolean isSuccess() {
        return status == AsyncSubagentStatus.COMPLETED;
    }
}
