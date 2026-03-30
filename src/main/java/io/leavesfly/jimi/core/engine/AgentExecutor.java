package io.leavesfly.jimi.core.engine;

import io.leavesfly.jimi.common.HttpClientConstants;
import io.leavesfly.jimi.common.ReactorUtils;
import io.leavesfly.jimi.core.agent.Agent;
import io.leavesfly.jimi.core.compaction.Compaction;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.core.engine.context.ContextManager;
import io.leavesfly.jimi.core.engine.toolcall.ToolDispatcher;
import io.leavesfly.jimi.core.engine.toolcall.ToolErrorTracker;
import io.leavesfly.jimi.core.hook.HookContext;
import io.leavesfly.jimi.core.hook.HookRegistry;
import io.leavesfly.jimi.core.hook.HookType;
import io.leavesfly.jimi.knowledge.memory.MemoryRecorder;
import io.leavesfly.jimi.llm.TokenCounter;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.ui.DebugLogger;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.message.ContentPartMessage;
import io.leavesfly.jimi.wire.message.StepBegin;
import io.leavesfly.jimi.wire.message.StepInterrupted;
import io.leavesfly.jimi.wire.message.TokenUsageMessage;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Agent 执行器（编排层）
 * <p>
 * 职责：
 * - 生命周期管理（初始化、成功/失败回调）
 * - Hook 触发
 * - Memory 记录
 * - 委托 ReactLoop 执行核心 ReAct 循环
 */
@Slf4j
public class AgentExecutor {

    // ==================== 核心依赖 ====================
    private final Agent agent;
    private final String agentName;
    private final JimiRuntime jimiRuntime;
    private final Context context;
    private final Wire wire;
    private final ToolRegistry toolRegistry;
    private final Compaction compaction;
    private final boolean isSubagent;

    // ==================== 组件依赖 ====================
    private final ExecutionState executionState;
    private final MemoryRecorder memoryRecorder;
    private final ContextManager contextManager;
    private final HookRegistry hookRegistry;
    private final ToolErrorTracker toolErrorTracker;

    private AgentExecutor(Builder builder) {
        this.agent = Objects.requireNonNull(builder.agent, "agent is required");
        this.jimiRuntime = Objects.requireNonNull(builder.jimiRuntime, "jimiRuntime is required");
        this.context = Objects.requireNonNull(builder.context, "context is required");
        this.wire = Objects.requireNonNull(builder.wire, "wire is required");
        this.toolRegistry = Objects.requireNonNull(builder.toolRegistry, "toolRegistry is required");
        this.compaction = Objects.requireNonNull(builder.compaction, "compaction is required");
        this.isSubagent = builder.isSubagent;
        this.agentName = agent.getName();

        this.executionState = new ExecutionState();
        this.memoryRecorder = Objects.requireNonNull(builder.memoryRecorder, "memoryRecorder is required");
        this.contextManager = Objects.requireNonNull(builder.contextManager, "contextManager is required");
        this.hookRegistry = Objects.requireNonNull(builder.hookRegistry, "hookRegistry is required");
        this.toolErrorTracker = builder.toolErrorTracker != null ? builder.toolErrorTracker : new ToolErrorTracker();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== 执行入口 ====================

    public Mono<Void> execute(List<ContentPart> userInput) {
        return execute(userInput, false);
    }

    public Mono<Void> execute(List<ContentPart> userInput, boolean skipKnowledge) {
        return Mono.defer(() -> {
            // 1. 初始化
            executionState.initializeTask();
            String userInputText = extractUserInputText(userInput);
            Message userMessage = Message.user(userInput);
            int userInputTokens = TokenCounter.estimateTokens(userMessage);

            executionState.addTokens(userInputTokens);
            executionState.setCurrentUserQuery(memoryRecorder.extractHighLevelIntent(userInput));

            // 2. 触发 PRE_USER_INPUT Hook
            HookContext preHookContext = HookContext.builder()
                    .hookType(HookType.PRE_USER_INPUT)
                    .workDir(jimiRuntime.getWorkDir())
                    .userInput(userInputText)
                    .agentName(agentName)
                    .build();

            return triggerHookSafely(HookType.PRE_USER_INPUT, preHookContext)
                    // 3. 准备上下文
                    .then(context.checkpoint(false))
                    .then(context.appendMessage(userMessage))
                    .then(context.updateTokenCount(context.getTokenCount() + userInputTokens))
                    .doOnSuccess(v -> log.info("Added user input: {} tokens", userInputTokens))
                    // 4. 执行 ReAct 循环
                    .then(runReactLoop())
                    // 5. 生命周期回调
                    .doOnSuccess(v -> onExecutionSuccess(userInputText))
                    .doOnError(this::onExecutionError);
        });
    }

    // ==================== ReAct 循环 ====================

    private Mono<Void> runReactLoop() {
        // 创建 ToolDispatcher
        ToolDispatcher toolDispatcher = new ToolDispatcher(
                toolRegistry, jimiRuntime.getWorkDir(), wire, toolErrorTracker, hookRegistry);

        // 创建 ReactLoop
        int maxSteps = jimiRuntime.getConfig().getLoopControl().getMaxStepsPerRun();
        ReactLoop reactLoop = new ReactLoop(
                jimiRuntime.getLlm(), toolDispatcher, jimiRuntime.getSession(), maxSteps);

        // 配置回调
        configureReactLoopCallbacks(reactLoop);

        // 执行循环
        List<Object> toolSchemas = new ArrayList<>(toolRegistry.getToolSchemas(agent.getTools()));
        return reactLoop.run(context, agent.getSystemPrompt(), toolSchemas)
                .onErrorResume(e -> {
                    wire.send(new StepInterrupted());
                    return Mono.error(e);
                });
    }

    private void configureReactLoopCallbacks(ReactLoop reactLoop) {
        // 步骤开始回调
        reactLoop.setOnStepBegin((localStep, globalStep) -> {
            executionState.setStepsInTask(localStep);
            wire.send(new StepBegin(globalStep, isSubagent, agentName));
            log.info("Agent '{}' step {}/{}", agentName != null ? agentName : "main", localStep, globalStep);
        });

        // 步骤前检查（上下文压缩）
        reactLoop.setBeforeStep(stepNo ->
                contextManager.checkAndCompact(context, jimiRuntime.getLlm(), compaction)
                        .then(context.checkpoint(false))
                        .then());

        // 流式内容回调
        reactLoop.setOnContentChunk(chunk -> {
            String contentDelta = chunk.getContentDelta();
            if (contentDelta != null && !contentDelta.isEmpty()) {
                ContentPartMessage.ContentType type = chunk.isReasoning()
                        ? ContentPartMessage.ContentType.REASONING
                        : ContentPartMessage.ContentType.NORMAL;
                wire.send(new ContentPartMessage(new TextPart(contentDelta), type));
            }
        });

        // Assistant 消息完成回调（Token 统计）
        reactLoop.setOnAssistantMessage((message, acc) -> {
            if (acc.getUsage() != null) {
                int newTokens = acc.getUsage().getTotalTokens();
                context.updateTokenCount(context.getTokenCount() + newTokens).subscribe();
                wire.send(new TokenUsageMessage(acc.getUsage()));
                DebugLogger.logLLMResponse(
                        message.getTextContent() != null ? message.getTextContent().length() : 0,
                        message.getToolCalls() != null ? message.getToolCalls().size() : 0,
                        acc.getUsage().getPromptTokens(),
                        acc.getUsage().getCompletionTokens(),
                        acc.getUsage().getTotalTokens());
            } else {
                int estimated = TokenCounter.estimateTokens(message);
                context.updateTokenCount(context.getTokenCount() + estimated).subscribe();
            }
        });
    }

    // ==================== 生命周期回调 ====================

    private void onExecutionSuccess(String userInputText) {
        log.info("Agent execution completed");
        memoryRecorder.recordTaskHistory(executionState, context, "success")
                .subscribe(unused -> {}, error -> log.warn("Failed to record task history", error));
        executionState.incrementTasksCompleted();

        HookContext postHookContext = HookContext.builder()
                .hookType(HookType.POST_USER_INPUT)
                .workDir(jimiRuntime.getWorkDir())
                .userInput(userInputText)
                .agentName(agentName)
                .build();
        triggerHookSafely(HookType.POST_USER_INPUT, postHookContext)
                .subscribe(unused -> {}, error -> log.warn("Hook POST_USER_INPUT failed", error));
    }

    private void onExecutionError(Throwable exception) {
        log.error("Agent execution failed", exception);
        memoryRecorder.recordTaskHistory(executionState, context, "failed")
                .subscribe(unused -> {}, error -> log.warn("Failed to record task history on error", error));

        HookContext errorHookContext = HookContext.builder()
                .hookType(HookType.ON_ERROR)
                .workDir(jimiRuntime.getWorkDir())
                .errorMessage(exception.getMessage())
                .errorStackTrace(getStackTraceString(exception))
                .agentName(agentName)
                .build();
        triggerHookSafely(HookType.ON_ERROR, errorHookContext)
                .subscribe(unused -> {}, error -> log.warn("Hook ON_ERROR failed", error));
    }

    // ==================== 辅助方法 ====================

    private Mono<Void> triggerHookSafely(HookType hookType, HookContext hookContext) {
        return ReactorUtils.triggerHookSafely(hookRegistry, hookType, hookContext);
    }

    private String extractUserInputText(List<ContentPart> userInput) {
        return userInput.stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).getText())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }

    private String getStackTraceString(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(stringWriter));
        String result = stringWriter.toString();
        if (result.length() > HttpClientConstants.MAX_STACK_TRACE_LENGTH) {
            return result.substring(0, HttpClientConstants.MAX_STACK_TRACE_LENGTH) + "\n... (truncated)";
        }
        return result;
    }

    // ==================== Getter ====================

    public Agent getAgent() { return agent; }
    public JimiRuntime getRuntime() { return jimiRuntime; }
    public Context getContext() { return context; }
    public Wire getWire() { return wire; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public boolean isSubagent() { return isSubagent; }
    public Compaction getCompaction() { return compaction; }

    // ==================== Builder ====================

    public static class Builder {
        private Agent agent;
        private JimiRuntime jimiRuntime;
        private Context context;
        private Wire wire;
        private ToolRegistry toolRegistry;
        private Compaction compaction;
        private MemoryRecorder memoryRecorder;
        private ContextManager contextManager;
        private HookRegistry hookRegistry;
        private ToolErrorTracker toolErrorTracker;
        private boolean isSubagent = false;

        public Builder agent(Agent agent) { this.agent = agent; return this; }
        public Builder runtime(JimiRuntime runtime) { this.jimiRuntime = runtime; return this; }
        public Builder context(Context context) { this.context = context; return this; }
        public Builder wire(Wire wire) { this.wire = wire; return this; }
        public Builder toolRegistry(ToolRegistry toolRegistry) { this.toolRegistry = toolRegistry; return this; }
        public Builder compaction(Compaction compaction) { this.compaction = compaction; return this; }
        public Builder memoryRecorder(MemoryRecorder memoryRecorder) { this.memoryRecorder = memoryRecorder; return this; }
        public Builder contextManager(ContextManager contextManager) { this.contextManager = contextManager; return this; }
        public Builder hookRegistry(HookRegistry hookRegistry) { this.hookRegistry = hookRegistry; return this; }
        public Builder toolErrorTracker(ToolErrorTracker tracker) { this.toolErrorTracker = tracker; return this; }
        public Builder isSubagent(boolean isSubagent) { this.isSubagent = isSubagent; return this; }

        public AgentExecutor build() { return new AgentExecutor(this); }
    }
}
