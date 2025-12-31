package io.leavesfly.jimi.core.engine.executor;

import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.knowledge.memory.*;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆记录器
 * <p>
 * 职责：
 * - 记录任务历史到持久化存储
 * - 记录会话摘要到持久化存储
 * - 记录错误模式到持久化存储
 * - 提取高层意图和关键发现
 */
@Slf4j
@Component
public class MemoryRecorder {

    @Autowired
    private ToolsMemoryExtractor memoryExtractor;

    @Autowired
    private MemoryManager memoryManager;

    public MemoryRecorder(ToolsMemoryExtractor memoryExtractor) {
        this.memoryExtractor = memoryExtractor;
    }

    /**
     * 设置 ToolsMemoryExtractor（用于 Spring Bean 注入后设置依赖）
     */
    public void setMemoryExtractor(ToolsMemoryExtractor memoryExtractor) {
        this.memoryExtractor = memoryExtractor;
    }

    /**
     * 记录任务历史到持久化存储
     *
     * @param state   执行状态
     * @param context 上下文
     * @param status  任务状态（success/failed/partial）
     * @return 完成的 Mono
     */
    public Mono<Void> recordTaskHistory(ExecutionState state, Context context, String status) {
        // 如果没有启用长期记忆或 ToolsMemoryExtractor 未初始化，直接返回
        if (memoryExtractor == null || memoryExtractor.getMemoryManager() == null) {
            return Mono.empty();
        }

        if (state.getCurrentUserQuery() == null || state.getTaskStartTime() == null) {
            log.warn("任务信息不完整，跳过记录");
            return Mono.empty();
        }

        return Mono.defer(() -> {
            // 计算执行时长
            long durationMs = state.getTaskDurationMs();

            // 提取任务摘要
            String summary = extractTaskSummary(context);

            // 生成任务ID
            String taskId = "task_" + state.getTaskStartTime().toString()
                    .replaceAll("[^0-9]", "").substring(0, 14);

            // 构建任务历史
            TaskHistory task = TaskHistory.builder()
                    .id(taskId)
                    .timestamp(state.getTaskStartTime())
                    .userQuery(state.getCurrentUserQuery())
                    .summary(summary)
                    .toolsUsed(new ArrayList<>(state.getToolsUsedInTask()))
                    .resultStatus(status)
                    .stepsCount(state.getStepsInTask())
                    .tokensUsed(state.getTokensInTask())
                    .durationMs(durationMs)
                    .build();

            // 根据关键词添加标签
            addTaskTags(task, state.getCurrentUserQuery(), state.getToolsUsedInTask());

            // 保存到 MemoryManager
            return memoryManager.addTaskHistory(task)
                    .doOnSuccess(v -> log.info("任务历史已记录: {} (用时{}ms, {}steps, {}tokens)",
                            state.getCurrentUserQuery(), durationMs,
                            state.getStepsInTask(), state.getTokensInTask()))
                    .doOnError(e -> log.error("记录任务历史失败", e))
                    .onErrorResume(e -> Mono.empty()); // 失败不影响主流程
        });
    }

    /**
     * 为任务添加标签
     */
    private void addTaskTags(TaskHistory task, String userQuery, List<String> toolsUsed) {
        if (userQuery.contains("修复") || userQuery.contains("解决") || userQuery.contains("bug")) {
            task.addTag("bug_fix");
        }
        if (userQuery.contains("实现") || userQuery.contains("添加") || userQuery.contains("增加")) {
            task.addTag("feature_add");
        }
        if (userQuery.contains("重构") || userQuery.contains("优化")) {
            task.addTag("refactor");
        }
        if (toolsUsed.isEmpty()) {
            task.addTag("query");
        }
    }

    /**
     * 提取任务摘要（从最后的 assistant 消息）
     *
     * @param context 上下文
     * @return 任务摘要
     */
    public String extractTaskSummary(Context context) {
        List<Message> history = context.getHistory();

        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg.getRole() == MessageRole.ASSISTANT) {
                String content = msg.getTextContent();
                if (content != null && !content.isEmpty()) {
                    // 取前 500 字符作为摘要
                    return content.length() > 500
                            ? content.substring(0, 500) + "..."
                            : content;
                }
            }
        }

        return "任务已完成";
    }

    /**
     * 记录会话摘要到持久化存储
     *
     * @param state   执行状态
     * @param context 上下文
     * @param status  会话状态（completed/interrupted/error）
     * @return 完成的 Mono
     */
    public Mono<Void> recordSessionSummary(ExecutionState state, Context context, String status) {
        if (memoryExtractor == null || memoryExtractor.getMemoryManager() == null) {
            return Mono.empty();
        }

        return Mono.defer(() -> {
            Instant startTime = state.getSessionStartTime();
            if (startTime == null) {
                startTime = state.getTaskStartTime();
            }

            if (startTime == null) {
                log.warn("会话开始时间未设置，跳过记录");
                return Mono.empty();
            }

            Instant endTime = Instant.now();

            // 生成会话ID
            String sessionId = "session_" + startTime.toString()
                    .replaceAll("[^0-9]", "").substring(0, 14);

            // 构建会话摘要
            SessionSummary session = SessionSummary.builder()
                    .sessionId(sessionId)
                    .startTime(startTime)
                    .endTime(endTime)
                    .goal(state.getCurrentUserQuery())
                    .outcome(extractTaskSummary(context))
                    .keyDecisions(new ArrayList<>(state.getKeyDecisions()))
                    .filesModified(new ArrayList<>(state.getFilesModified()))
                    .tasksCompleted(state.getTasksCompletedInSession())
                    .totalSteps(state.getStepsInTask())
                    .totalTokens(state.getTokensInTask())
                    .status(status)
                    .lessonsLearned(new ArrayList<>(state.getLessonsLearned()))
                    .build();

            // 保存到 MemoryManager
            return memoryExtractor.getMemoryManager().addSessionSummary(session)
                    .doOnSuccess(v -> log.info("会话摘要已记录: {} ({})", sessionId, status))
                    .doOnError(e -> log.error("记录会话摘要失败", e))
                    .onErrorResume(e -> Mono.empty());
        });
    }

    /**
     * 记录错误模式到持久化存储
     *
     * @param e           异常
     * @param toolName    工具/场景名称
     * @param contextInfo 上下文信息
     * @return 完成的 Mono
     */
    public Mono<Void> recordErrorPattern(Throwable e, String toolName, String contextInfo) {
        if (memoryExtractor == null || memoryExtractor.getMemoryManager() == null) {
            return Mono.empty();
        }

        return Mono.defer(() -> {
            // 提取错误类型
            String errorType = e.getClass().getSimpleName();

            // 提取错误消息（截取前 200 字符）
            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.length() > 200) {
                errorMessage = errorMessage.substring(0, 200) + "...";
            }

            // 提取上下文（截取前 100 字符）
            String contextStr = contextInfo;
            if (contextStr != null && contextStr.length() > 100) {
                contextStr = contextStr.substring(0, 100) + "...";
            }

            // 生成ID
            String patternId = "err_" + Instant.now().toString()
                    .replaceAll("[^0-9]", "").substring(0, 14);

            // 构建错误模式
            ErrorPattern pattern = ErrorPattern.builder()
                    .id(patternId)
                    .errorType(errorType)
                    .errorMessage(errorMessage != null ? errorMessage : "Unknown error")
                    .context(contextStr)
                    .toolName(toolName)
                    .firstSeen(Instant.now())
                    .lastSeen(Instant.now())
                    .severity(classifyErrorSeverity(e))
                    .build();

            // 保存到 MemoryManager
            return memoryExtractor.getMemoryManager().addOrUpdateErrorPattern(pattern)
                    .doOnSuccess(v -> log.debug("错误模式已记录: {} - {}", errorType, toolName))
                    .doOnError(err -> log.error("记录错误模式失败", err))
                    .onErrorResume(err -> Mono.empty());
        });
    }

    /**
     * 分类错误严重程度
     */
    private String classifyErrorSeverity(Throwable e) {
        if (e instanceof OutOfMemoryError || e instanceof StackOverflowError) {
            return "high";
        }
        if (e instanceof RuntimeException) {
            return "medium";
        }
        return "low";
    }

    /**
     * 提取高层意图（简化版：取用户输入的前 500 字符）
     *
     * @param userInput 用户输入
     * @return 高层意图
     */
    public String extractHighLevelIntent(List<ContentPart> userInput) {
        String fullText = userInput.stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).getText())
                .collect(Collectors.joining(" "));

        return fullText.length() > 500
                ? fullText.substring(0, 500) + "..."
                : fullText;
    }

    /**
     * 从工具结果提取关键发现（简化版：取输出的前 100 字符）
     *
     * @param result        工具执行结果
     * @param toolSignature 工具签名
     * @return 关键发现，如果无法提取则返回 null
     */
    public String extractInsightFromToolResult(ToolResult result, String toolSignature) {
        String output = result.getOutput();
        if (output == null || output.isEmpty()) {
            return null;
        }

        String preview = output.length() > 100
                ? output.substring(0, 100) + "..."
                : output;

        String toolName = toolSignature.split(":")[0];
        return String.format("[%s] %s", toolName, preview);
    }

    /**
     * 提取最新思考（从最后一条 assistant 消息）
     *
     * @param context 上下文
     * @return 最新思考内容
     */
    public String extractLatestThought(Context context) {
        List<Message> history = context.getHistory();

        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg.getRole() == MessageRole.ASSISTANT) {
                String content = msg.getTextContent();
                if (content != null && !content.isEmpty()) {
                    return content.length() > 200
                            ? content.substring(0, 200) + "..."
                            : content;
                }
            }
        }

        return "(无)";
    }

    /**
     * 从工具结果中提取长期记忆（委托给 ToolsMemoryExtractor）
     *
     * @param result   工具结果
     * @param toolName 工具名称
     * @return 完成的 Mono
     */
    public Mono<Void> extractFromToolResult(ToolResult result, String toolName) {
        if (memoryExtractor == null) {
            return Mono.empty();
        }

        return memoryExtractor.extractFromToolResult(result, toolName)
                .onErrorResume(e -> {
                    log.warn("从工具结果提取记忆失败: {}", e.getMessage());
                    return Mono.empty();
                });
    }
}
