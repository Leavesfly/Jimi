package io.leavesfly.jimi.team;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 团队任务
 * <p>
 * 表示 SharedTaskList 中的一个可认领任务单元。
 * 支持优先级排序、依赖检查和原子状态转换。
 */
@Data
@Slf4j
public class TeamTask {

    private final String taskId;
    private final String description;
    private final int priority;
    private final List<String> dependencies;

    private volatile TaskStatus status;
    private volatile String claimedBy;
    private volatile String result;
    private volatile Instant claimedAt;
    private volatile Instant completedAt;

    public TeamTask(String taskId, String description, int priority, List<String> dependencies) {
        this.taskId = taskId;
        this.description = description;
        this.priority = priority;
        this.dependencies = dependencies != null ? new ArrayList<>(dependencies) : new ArrayList<>();
        this.status = TaskStatus.PENDING;
    }

    /**
     * 尝试认领任务（线程安全）
     *
     * @param teammateId 认领者 ID
     * @return 是否认领成功
     */
    public synchronized boolean tryClaim(String teammateId) {
        if (status != TaskStatus.PENDING) {
            return false;
        }
        this.status = TaskStatus.CLAIMED;
        this.claimedBy = teammateId;
        this.claimedAt = Instant.now();
        log.debug("Task {} claimed by {}", taskId, teammateId);
        return true;
    }

    /**
     * 标记任务开始执行
     */
    public synchronized void markInProgress() {
        if (status == TaskStatus.CLAIMED) {
            this.status = TaskStatus.IN_PROGRESS;
        }
    }

    /**
     * 标记任务完成
     *
     * @param taskResult 执行结果
     */
    public synchronized void markCompleted(String taskResult) {
        this.status = TaskStatus.COMPLETED;
        this.result = taskResult;
        this.completedAt = Instant.now();
        log.debug("Task {} completed by {}", taskId, claimedBy);
    }

    /**
     * 标记任务失败
     *
     * @param errorMessage 错误信息
     */
    public synchronized void markFailed(String errorMessage) {
        this.status = TaskStatus.FAILED;
        this.result = errorMessage;
        this.completedAt = Instant.now();
        log.warn("Task {} failed: {}", taskId, errorMessage);
    }

    /**
     * 检查任务是否已终结（完成、失败或取消）
     */
    public boolean isTerminal() {
        TaskStatus currentStatus = this.status;
        return currentStatus == TaskStatus.COMPLETED
                || currentStatus == TaskStatus.FAILED
                || currentStatus == TaskStatus.CANCELLED;
    }
}
