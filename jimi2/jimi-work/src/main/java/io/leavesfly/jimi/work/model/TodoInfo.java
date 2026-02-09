package io.leavesfly.jimi.work.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Todo 信息 - 执行计划项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoInfo {

    public enum Status {
        PENDING, IN_PROGRESS, DONE, CANCELLED, ERROR
    }

    private String id;
    private String content;
    private Status status;
    private String parentId;

    /**
     * Todo 列表汇总
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TodoList {
        private List<TodoInfo> todos;
        private int totalCount;
        private int pendingCount;
        private int inProgressCount;
        private int doneCount;
        private int cancelledCount;
        private int errorCount;
    }

    /**
     * 解析状态字符串
     */
    public static Status parseStatus(String status) {
        if (status == null) return Status.PENDING;
        return switch (status.toUpperCase()) {
            case "IN_PROGRESS", "RUNNING" -> Status.IN_PROGRESS;
            case "DONE", "COMPLETE", "COMPLETED" -> Status.DONE;
            case "CANCELLED", "CANCELED" -> Status.CANCELLED;
            case "ERROR", "FAILED" -> Status.ERROR;
            default -> Status.PENDING;
        };
    }
}
