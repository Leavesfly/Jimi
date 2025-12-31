package io.leavesfly.jimi.core.engine.executor;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;

import java.util.List;

/**
 * 执行状态管理器
 * <p>
 * 职责：
 * - 跟踪任务执行状态（开始时间、工具使用、步数、Token数）
 * - 跟踪会话状态（修改的文件、关键决策、经验教训）
 * - 跟踪连续思考步数（无工具调用检测）
 * - 管理 ReCAP 父级上下文栈和递归深度
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
     * 任务中使用的Token数
     */
    private int tokensInTask = 0;

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
     * 经验教训列表
     */
    private final List<String> lessonsLearned = new ArrayList<>();

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
    public void initializeTask() {
        taskStartTime = Instant.now();
        toolsUsedInTask.clear();
        stepsInTask = 0;
        tokensInTask = 0;
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
        lessonsLearned.clear();
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
    public void incrementStep() {
        stepsInTask++;
        log.debug("步数递增: {}", stepsInTask);
    }

    /**
     * 累加Token数
     *
     * @param tokens 要添加的Token数
     */
    public void addTokens(int tokens) {
        tokensInTask += tokens;
        log.debug("Token累加: {} (总计: {})", tokens, tokensInTask);
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
     * 记录经验教训
     *
     * @param lesson 经验描述
     */
    public void recordLessonLearned(String lesson) {
        if (lesson != null && !lesson.isEmpty() && !lessonsLearned.contains(lesson)) {
            lessonsLearned.add(lesson);
            log.debug("记录经验教训: {}", lesson);
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
     * 计算任务执行时长（毫秒）
     *
     * @return 执行时长，如果任务未开始返回 0
     */
    public long getTaskDurationMs() {
        if (taskStartTime == null) {
            return 0;
        }
        return Instant.now().toEpochMilli() - taskStartTime.toEpochMilli();
    }

    /**
     * 计算会话时长（毫秒）
     *
     * @return 会话时长，如果会话未开始返回 0
     */
    public long getSessionDurationMs() {
        if (sessionStartTime == null) {
            return 0;
        }
        return Instant.now().toEpochMilli() - sessionStartTime.toEpochMilli();
    }


    /**
     * 重置所有状态（用于测试或完全重置）
     */
    public void reset() {
        initializeTask();
        initializeSession();
        log.debug("执行状态已完全重置");
    }
}
