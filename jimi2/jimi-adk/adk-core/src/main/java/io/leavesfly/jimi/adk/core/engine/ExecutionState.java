package io.leavesfly.jimi.adk.core.engine;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 执行状态门面
 * <p>
 * 组合 {@link TaskMetrics}（任务级度量）、{@link LoopGuard}（循环守卫）
 * 和会话级跟踪数据。各关注点生命周期独立：
 * <ul>
 *   <li>TaskMetrics：每次任务重置</li>
 *   <li>LoopGuard：每次任务重置</li>
 *   <li>会话跟踪：整个会话期间累积</li>
 * </ul>
 * </p>
 */
@Slf4j
@Getter
public class ExecutionState {

    // ==================== 组合组件 ====================

    /** 任务级度量 */
    private final TaskMetrics taskMetrics = new TaskMetrics();

    /** 循环守卫 */
    private final LoopGuard loopGuard = new LoopGuard();

    // ==================== 会话级跟踪 ====================

    /** 会话开始时间 */
    private Instant sessionStartTime;

    /** 修改的文件列表 */
    private final List<String> filesModified = new ArrayList<>();

    /** 关键决策列表 */
    private final List<String> keyDecisions = new ArrayList<>();

    /** 会话中完成的任务数 */
    private int tasksCompletedInSession = 0;

    // ==================== 会话级方法 ====================

    /**
     * 初始化会话状态
     */
    public void initializeSession() {
        sessionStartTime = Instant.now();
        filesModified.clear();
        keyDecisions.clear();
        tasksCompletedInSession = 0;
        log.debug("会话状态已初始化: startTime={}", sessionStartTime);
    }

    /**
     * 记录修改的文件
     */
    public void recordFileModified(String filePath) {
        if (filePath != null && !filePath.isEmpty() && !filesModified.contains(filePath)) {
            filesModified.add(filePath);
            log.debug("记录文件修改: {}", filePath);
        }
    }

    /**
     * 记录关键决策
     */
    public void recordKeyDecision(String decision) {
        if (decision != null && !decision.isEmpty() && !keyDecisions.contains(decision)) {
            keyDecisions.add(decision);
            log.debug("记录关键决策: {}", decision);
        }
    }

    /**
     * 增加完成的任务数
     */
    public void incrementTasksCompleted() {
        tasksCompletedInSession++;
        log.debug("任务完成数递增: {}", tasksCompletedInSession);
    }

    /**
     * 获取会话时长
     */
    public Duration getSessionDuration() {
        return sessionStartTime != null ? Duration.between(sessionStartTime, Instant.now()) : Duration.ZERO;
    }

    /**
     * 获取不可变的文件修改列表
     */
    public List<String> getFilesModified() {
        return Collections.unmodifiableList(filesModified);
    }

    /**
     * 获取不可变的关键决策列表
     */
    public List<String> getKeyDecisions() {
        return Collections.unmodifiableList(keyDecisions);
    }

    /**
     * 重置所有状态
     */
    public void reset() {
        taskMetrics.initialize(null);
        loopGuard.reset();
        initializeSession();
        log.debug("执行状态已完全重置");
    }

    /**
     * 生成执行摘要
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("执行摘要: ").append(taskMetrics.getSummary());
        if (!filesModified.isEmpty()) {
            sb.append(", 修改文件=").append(filesModified.size());
        }
        return sb.toString();
    }
}
