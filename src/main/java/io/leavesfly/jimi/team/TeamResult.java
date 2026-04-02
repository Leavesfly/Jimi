package io.leavesfly.jimi.team;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 团队执行结果
 * <p>
 * 汇总所有 Teammate 的任务执行结果，供 Main Agent 使用。
 */
@Data
@Builder
public class TeamResult {

    private final String teamId;
    private final boolean success;
    private final Instant startTime;
    private final Instant endTime;

    @Builder.Default
    private final List<TaskResult> taskResults = new ArrayList<>();

    @Builder.Default
    private final List<String> errors = new ArrayList<>();

    /**
     * 获取执行耗时
     */
    public Duration getDuration() {
        if (startTime == null || endTime == null) {
            return Duration.ZERO;
        }
        return Duration.between(startTime, endTime);
    }

    /**
     * 生成结果摘要
     */
    public String toSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Team [%s] %s in %s\n",
                teamId,
                success ? "completed successfully" : "completed with errors",
                getDuration()));

        int completed = 0;
        int failed = 0;
        for (TaskResult taskResult : taskResults) {
            if (taskResult.isSuccess()) {
                completed++;
            } else {
                failed++;
            }
        }
        summary.append(String.format("Tasks: %d completed, %d failed, %d total\n",
                completed, failed, taskResults.size()));

        summary.append("\n--- Task Results ---\n");
        for (TaskResult taskResult : taskResults) {
            summary.append(String.format("[%s] %s (by %s): %s\n",
                    taskResult.getTaskId(),
                    taskResult.isSuccess() ? "✅" : "❌",
                    taskResult.getExecutedBy(),
                    truncate(taskResult.getResult(), 200)));
        }

        if (!errors.isEmpty()) {
            summary.append("\n--- Errors ---\n");
            for (String error : errors) {
                summary.append("- ").append(error).append("\n");
            }
        }

        return summary.toString();
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "(no result)";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    /**
     * 单个任务的执行结果
     */
    @Data
    @Builder
    public static class TaskResult {
        private final String taskId;
        private final String description;
        private final String executedBy;
        private final boolean success;
        private final String result;
    }
}
