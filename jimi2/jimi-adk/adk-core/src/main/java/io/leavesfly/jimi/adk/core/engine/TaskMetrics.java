package io.leavesfly.jimi.adk.core.engine;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 任务级度量
 * <p>
 * 职责：跟踪单次任务执行的度量数据（步数、Token 数、工具使用、执行时长）。
 * 每次新任务开始时重置。
 * </p>
 */
@Slf4j
@Getter
public class TaskMetrics {

    /** 任务开始时间 */
    private Instant startTime;

    /** 当前用户查询 */
    private String userQuery;

    /** 任务中使用的工具（去重） */
    private final List<String> toolsUsed = new ArrayList<>();

    /** 步数 */
    private int steps = 0;

    /** 总 Token 数 */
    private int totalTokens = 0;

    /** 输入 Token 数 */
    private int inputTokens = 0;

    /** 输出 Token 数 */
    private int outputTokens = 0;

    /**
     * 初始化（每个新任务开始时调用）
     */
    public void initialize(String userQuery) {
        this.startTime = Instant.now();
        this.userQuery = userQuery;
        this.toolsUsed.clear();
        this.steps = 0;
        this.totalTokens = 0;
        this.inputTokens = 0;
        this.outputTokens = 0;
        log.debug("TaskMetrics 已初始化: startTime={}", startTime);
    }

    /**
     * 增加步数并返回当前步数
     */
    public int incrementStep() {
        steps++;
        log.debug("步数递增: {}", steps);
        return steps;
    }

    /**
     * 累加 Token 数
     */
    public void addTokens(int total, int input, int output) {
        this.totalTokens += total;
        this.inputTokens += input;
        this.outputTokens += output;
        log.debug("Token 累加: total={} (累计: {}), input={}, output={}",
                total, this.totalTokens, this.inputTokens, this.outputTokens);
    }

    /**
     * 记录工具使用（去重）
     */
    public void recordToolUsed(String toolName) {
        if (toolName != null && !toolName.isEmpty() && !toolsUsed.contains(toolName)) {
            toolsUsed.add(toolName);
            log.debug("记录工具使用: {}", toolName);
        }
    }

    /**
     * 获取任务执行时长
     */
    public Duration getDuration() {
        return startTime != null ? Duration.between(startTime, Instant.now()) : Duration.ZERO;
    }

    /**
     * 获取任务执行时长（毫秒）
     */
    public long getDurationMs() {
        return getDuration().toMillis();
    }

    /**
     * 获取不可变的工具列表
     */
    public List<String> getToolsUsed() {
        return Collections.unmodifiableList(toolsUsed);
    }

    /**
     * 生成度量摘要
     */
    public String getSummary() {
        return String.format("步数=%d, Token=%d, 工具=%d种, 耗时=%dms",
                steps, totalTokens, toolsUsed.size(), getDurationMs());
    }
}
