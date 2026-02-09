package io.leavesfly.jimi.adk.core.engine;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 执行状态管理器
 * <p>
 * 职责：
 * - 跟踪任务执行状态（开始时间、工具使用、步数、Token 数）
 * - 跟踪会话状态（修改的文件、关键决策）
 * - 跟踪连续思考步数（无工具调用检测）
 * - 提供执行统计信息
 */
@Slf4j
@Getter
@Setter
public class ExecutionState {

    // ==================== 任务执行跟踪 ====================

    /**
     * 任务开始时间
     */
    private Instant taskStartTime;

    /**
     * 当前用户查询
     */
    private String currentUserQuery;

    /**
     * 任务中使用的工具列表
     */
    private final List<String> toolsUsedInTask = new ArrayList<>();

    /**
     * 任务中的步数
     */
    private int stepsInTask = 0;

    /**
     * 任务中使用的 Token 数
     */
    private int tokensInTask = 0;

    /**
     * 输入 Token 数
     */
    private int inputTokens = 0;

    /**
     * 输出 Token 数
     */
    private int outputTokens = 0;

    // ==================== 会话跟踪 ====================

    /**
     * 会话开始时间
     */
    private Instant sessionStartTime;

    /**
     * 修改的文件列表
     */
    private final List<String> filesModified = new ArrayList<>();

    /**
     * 关键决策列表
     */
    private final List<String> keyDecisions = new ArrayList<>();

    /**
     * 会话中完成的任务数
     */
    private int tasksCompletedInSession = 0;

    // ==================== 思考步数跟踪 ====================

    /**
     * 连续无工具调用步数
     */
    private int consecutiveNoToolCallSteps = 0;

    /**
     * 初始化任务状态（在每个新任务开始时调用）
     */
    public void initializeTask(String userQuery) {
        taskStartTime = Instant.now();
        currentUserQuery = userQuery;
        toolsUsedInTask.clear();
        stepsInTask = 0;
        tokensInTask = 0;
        inputTokens = 0;
        outputTokens = 0;
        consecutiveNoToolCallSteps = 0;

        log.debug("任务状态已初始化: startTime={}", taskStartTime);
    }

    /**
     * 初始化会话状态（在会话开始时调用）
     */
    public void initializeSession() {
        sessionStartTime = Instant.now();
        filesModified.clear();
        keyDecisions.clear();
        tasksCompletedInSession = 0;

        log.debug("会话状态已初始化: startTime={}", sessionStartTime);
    }

    /**
     * 记录工具使用
     *
     * @param toolName 工具名称
     */
    public void recordToolUsed(String toolName) {
        if (toolName != null && !toolName.isEmpty() && !toolsUsedInTask.contains(toolName)) {
            toolsUsedInTask.add(toolName);
            log.debug("记录工具使用: {}", toolName);
        }
    }

    /**
     * 增加步数
     */
    public int incrementStep() {
        stepsInTask++;
        log.debug("步数递增: {}", stepsInTask);
        return stepsInTask;
    }

    /**
     * 累加 Token 数
     *
     * @param total  总 Token 数
     * @param input  输入 Token 数
     * @param output 输出 Token 数
     */
    public void addTokens(int total, int input, int output) {
        tokensInTask += total;
        inputTokens += input;
        outputTokens += output;
        log.debug("Token 累加: total={} (累计: {}), input={}, output={}",
                total, tokensInTask, inputTokens, outputTokens);
    }

    /**
     * 累加 Token 数（仅总量）
     */
    public void addTokens(int total) {
        addTokens(total, 0, 0);
    }

    /**
     * 检查是否应该强制完成（连续思考步数超限）
     *
     * @param maxThinkingSteps 最大连续思考步数
     * @return true 如果应该强制完成
     */
    public boolean shouldForceComplete(int maxThinkingSteps) {
        consecutiveNoToolCallSteps++;

        if (consecutiveNoToolCallSteps >= maxThinkingSteps) {
            log.warn("连续思考 {} 步未调用工具，强制完成", consecutiveNoToolCallSteps);
            return true;
        }

        log.debug("连续思考步数: {}/{}", consecutiveNoToolCallSteps, maxThinkingSteps);
        return false;
    }

    /**
     * 重置无工具调用计数器（当有工具调用时）
     */
    public void resetNoToolCallCounter() {
        if (consecutiveNoToolCallSteps > 0) {
            log.debug("重置连续思考计数器 (之前: {})", consecutiveNoToolCallSteps);
        }
        consecutiveNoToolCallSteps = 0;
    }

    /**
     * 记录修改的文件
     *
     * @param filePath 文件路径
     */
    public void recordFileModified(String filePath) {
        if (filePath != null && !filePath.isEmpty() && !filesModified.contains(filePath)) {
            filesModified.add(filePath);
            log.debug("记录文件修改: {}", filePath);
        }
    }

    /**
     * 记录关键决策
     *
     * @param decision 决策描述
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
     * 获取任务执行时长
     *
     * @return 执行时长
     */
    public Duration getTaskDuration() {
        if (taskStartTime == null) {
            return Duration.ZERO;
        }
        return Duration.between(taskStartTime, Instant.now());
    }

    /**
     * 获取任务执行时长（毫秒）
     */
    public long getTaskDurationMs() {
        return getTaskDuration().toMillis();
    }

    /**
     * 获取会话时长
     *
     * @return 会话时长
     */
    public Duration getSessionDuration() {
        if (sessionStartTime == null) {
            return Duration.ZERO;
        }
        return Duration.between(sessionStartTime, Instant.now());
    }

    /**
     * 获取不可变的工具列表
     */
    public List<String> getToolsUsedInTask() {
        return Collections.unmodifiableList(toolsUsedInTask);
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
        initializeTask(null);
        initializeSession();
        log.debug("执行状态已完全重置");
    }

    /**
     * 生成执行摘要
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("执行摘要: ");
        sb.append("步数=").append(stepsInTask);
        sb.append(", Token=").append(tokensInTask);
        sb.append(", 工具=").append(toolsUsedInTask.size()).append("种");
        sb.append(", 耗时=").append(getTaskDurationMs()).append("ms");
        if (!filesModified.isEmpty()) {
            sb.append(", 修改文件=").append(filesModified.size());
        }
        return sb.toString();
    }
}
