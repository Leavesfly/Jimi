package io.leavesfly.jimi.team;

/**
 * 团队任务状态枚举
 */
public enum TaskStatus {

    /**
     * 待认领：任务已创建，等待 Teammate 认领
     */
    PENDING,

    /**
     * 已认领：Teammate 已认领但尚未开始执行
     */
    CLAIMED,

    /**
     * 执行中：Teammate 正在执行任务
     */
    IN_PROGRESS,

    /**
     * 已完成：任务执行成功
     */
    COMPLETED,

    /**
     * 失败：任务执行失败
     */
    FAILED,

    /**
     * 已取消：任务被取消
     */
    CANCELLED
}
