package io.leavesfly.jimi.core.team;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 共享任务列表 — Agent Teams 的核心协调数据结构
 * <p>
 * 线程安全，支持多个 Teammate 并发认领和更新任务。
 * 使用 CAS 原子操作防止任务被重复认领。
 */
@Slf4j
public class SharedTaskList {

    @Getter
    private final String teamId;
    private final ConcurrentMap<String, TeamTask> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger taskIdCounter = new AtomicInteger(0);

    public SharedTaskList(String teamId) {
        this.teamId = teamId;
    }

    /**
     * 添加任务
     *
     * @param description  任务描述
     * @param priority     优先级（越小越高）
     * @param dependencies 依赖的 taskId 列表
     * @return 创建的任务
     */
    public TeamTask addTask(String description, int priority, List<String> dependencies) {
        String taskId = "task-" + taskIdCounter.incrementAndGet();
        TeamTask task = new TeamTask(taskId, description, priority, dependencies);
        tasks.put(taskId, task);
        log.info("[Team {}] Task added: {} - {}", teamId, taskId, description);
        return task;
    }

    /**
     * 认领一个可执行的任务
     * <p>
     * 按优先级排序，选择依赖已满足的第一个 PENDING 任务进行认领。
     * 使用 synchronized 在 TeamTask 内部保证原子性。
     *
     * @param teammateId 认领者 ID
     * @return 认领到的任务，如果没有可认领的任务则返回 empty
     */
    public Optional<TeamTask> claimTask(String teammateId) {
        List<TeamTask> claimable = getClaimableTasks();
        for (TeamTask task : claimable) {
            if (task.tryClaim(teammateId)) {
                log.info("[Team {}] Teammate '{}' claimed task: {} - {}",
                        teamId, teammateId, task.getTaskId(), task.getDescription());
                return Optional.of(task);
            }
        }
        return Optional.empty();
    }

    /**
     * 获取所有可认领的任务（PENDING 且依赖已满足），按优先级排序
     */
    public List<TeamTask> getClaimableTasks() {
        return tasks.values().stream()
                .filter(task -> task.getStatus() == TaskStatus.PENDING)
                .filter(this::areDependenciesSatisfied)
                .sorted(Comparator.comparingInt(TeamTask::getPriority))
                .collect(Collectors.toList());
    }

    /**
     * 检查任务的所有依赖是否已完成
     */
    private boolean areDependenciesSatisfied(TeamTask task) {
        for (String depId : task.getDependencies()) {
            TeamTask depTask = tasks.get(depId);
            if (depTask == null || depTask.getStatus() != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查是否所有任务都已终结
     */
    public boolean isAllCompleted() {
        return tasks.values().stream().allMatch(TeamTask::isTerminal);
    }

    /**
     * 检查是否还有待处理的任务（PENDING 或正在执行中）
     */
    public boolean hasPendingWork() {
        return tasks.values().stream().anyMatch(task -> !task.isTerminal());
    }

    /**
     * 获取指定任务
     */
    public Optional<TeamTask> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * 获取所有任务
     */
    public List<TeamTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * 获取进度摘要
     */
    public String getProgressSummary() {
        long pending = tasks.values().stream().filter(t -> t.getStatus() == TaskStatus.PENDING).count();
        long claimed = tasks.values().stream().filter(t -> t.getStatus() == TaskStatus.CLAIMED).count();
        long inProgress = tasks.values().stream().filter(t -> t.getStatus() == TaskStatus.IN_PROGRESS).count();
        long completed = tasks.values().stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED).count();
        long failed = tasks.values().stream().filter(t -> t.getStatus() == TaskStatus.FAILED).count();

        return String.format("[Team %s] Progress: %d pending, %d claimed, %d in-progress, %d completed, %d failed (total: %d)",
                teamId, pending, claimed, inProgress, completed, failed, tasks.size());
    }
}
