package io.leavesfly.jimi.core.loop;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * Loop 运行状态
 * <p>
 * 表示当前 /loop 或 /goal 命令的执行状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoopStatus {

    /**
     * 是否正在运行
     */
    private boolean running;

    /**
     * 是否已暂停
     */
    private boolean paused;

    /**
     * 已完成的迭代次数
     */
    private int iterationCount;

    /**
     * 当前执行的 prompt
     */
    private String prompt;

    /**
     * 循环间隔（仅 /loop 模式）
     */
    private Duration interval;

    /**
     * 循环启动时间
     */
    private Instant startTime;

    /**
     * 下次执行时间（仅 /loop 模式）
     */
    private Instant nextExecutionTime;

    /**
     * 循环类型
     */
    private LoopType type;

    /**
     * Loop 类型枚举
     */
    public enum LoopType {
        /** 按时间间隔重复执行 */
        INTERVAL,
        /** 目标驱动迭代 */
        GOAL
    }
}
