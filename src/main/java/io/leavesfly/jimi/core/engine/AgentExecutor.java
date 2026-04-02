package io.leavesfly.jimi.core.engine;

import io.leavesfly.jimi.core.agent.Agent;
import io.leavesfly.jimi.core.compaction.Compaction;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.core.engine.context.ContextManager;
import io.leavesfly.jimi.core.engine.toolcall.ToolDispatcher;
import io.leavesfly.jimi.core.engine.toolcall.ToolErrorTracker;
import io.leavesfly.jimi.core.hook.HookContext;
import io.leavesfly.jimi.core.hook.HookRegistry;
import io.leavesfly.jimi.core.hook.HookType;
import io.leavesfly.jimi.memory.MemoryConsolidator;
import io.leavesfly.jimi.memory.MemoryExtractor;
import io.leavesfly.jimi.memory.MemoryManager;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Agent 执行器（编排层）
 * <p>
 * 职责：
 * - 生命周期管理（初始化、成功/失败回调）
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
    private final MemoryManager memoryManager;

    private final ContextManager contextManager;
    private final ToolErrorTracker toolErrorTracker;
    private final HookRegistry hookRegistry;

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
        this.memoryManager = builder.memoryManager;
        this.contextManager = Objects.requireNonNull(builder.contextManager, "contextManager is required");
        this.toolErrorTracker = builder.toolErrorTracker != null ? builder.toolErrorTracker : new ToolErrorTracker();
        this.hookRegistry = builder.hookRegistry;
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

            // 触发 USER_PROMPT_SUBMIT hook
            HookContext userPromptHookContext = HookContext.builder()
                    .hookType(HookType.USER_PROMPT_SUBMIT)
                    .workDir(jimiRuntime.getWorkDir())
                    .sessionId(jimiRuntime.getSession().getId())
                    .build();

            return triggerHookSafely(HookType.USER_PROMPT_SUBMIT, userPromptHookContext)
                    .then(context.checkpoint(false))
                    .then(context.appendMessage(userMessage))
                    .then(context.updateTokenCount(context.getTokenCount() + userInputTokens))
                    .doOnSuccess(v -> log.info("Added user input: {} tokens", userInputTokens))
                    // 4. 按需注入相关 Topic 文件（Layer 2 记忆）
                    .then(contextManager.matchAndInjectTopics(
                            context, jimiRuntime.getWorkDir().toAbsolutePath().toString()))
                    // 5. 执行 ReAct 循环
                    .then(runReactLoop())
                    // 5. 生命周期回调
                    .doOnSuccess(v -> onExecutionSuccess(userInputText))
                    .doOnError(this::onExecutionError);
        });
    }

    // ==================== ReAct 循环 ====================

    private Mono<Void> runReactLoop() {
        // 创建 ToolDispatcher（注入 HookRegistry）
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

        // 异步提取记忆 + 整理检查（不阻塞主流程）
        if (memoryManager != null && !isSubagent) {
            try {
                String workDirPath = jimiRuntime.getWorkDir().toAbsolutePath().toString();

                // extractMemories: 从本轮执行中提取有价值的记忆
                MemoryExtractor extractor = new MemoryExtractor(memoryManager);
                extractor.extract(workDirPath, executionState, context, "success");

                // autoDream: 检查是否需要触发后台整理
                int sessionTaskCount = executionState.getTasksCompletedInSession();
                MemoryConsolidator consolidator = new MemoryConsolidator(memoryManager);
                consolidator.consolidateIfNeeded(workDirPath, sessionTaskCount, jimiRuntime.getLlm())
                        .subscribe(
                                unused -> {},
                                error -> log.warn("Memory consolidation failed", error));
            } catch (Exception e) {
                log.warn("Memory extraction/consolidation failed, skipping", e);
            }
        }

        executionState.incrementTasksCompleted();

        // 触发 STOP hook（会话步骤完成）
        HookContext stopHookContext = HookContext.builder()
                .hookType(HookType.STOP)
                .workDir(jimiRuntime.getWorkDir())
                .sessionId(jimiRuntime.getSession().getId())
                .stopHookActive(true)
                .build();
        triggerHookSafely(HookType.STOP, stopHookContext).subscribe();
    }

    private void onExecutionError(Throwable exception) {
        log.error("Agent execution failed", exception);

        // 触发 ON_ERROR hook
        HookContext errorHookContext = HookContext.builder()
                .hookType(HookType.ON_ERROR)
                .workDir(jimiRuntime.getWorkDir())
                .sessionId(jimiRuntime.getSession().getId())
                .errorMessage(exception.getMessage())
                .build();
        triggerHookSafely(HookType.ON_ERROR, errorHookContext).subscribe();
    }

    // ==================== 辅助方法 ====================

    private String extractUserInputText(List<ContentPart> userInput) {
        return userInput.stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).getText())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }

    // ==================== Getter ====================

    public Agent getAgent() { return agent; }
    public JimiRuntime getRuntime() { return jimiRuntime; }
    public Context getContext() { return context; }
    public Wire getWire() { return wire; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public boolean isSubagent() { return isSubagent; }
    public Compaction getCompaction() { return compaction; }
    public HookRegistry getHookRegistry() { return hookRegistry; }

    /**
     * 安全触发 Hook（不影响主流程）
     */
    private Mono<Void> triggerHookSafely(HookType type, HookContext hookContext) {
        if (hookRegistry == null) {
            return Mono.empty();
        }
        return hookRegistry.trigger(type, hookContext)
                .onErrorResume(e -> {
                    log.warn("Hook trigger failed for {}: {}", type, e.getMessage());
                    return Mono.empty();
                });
    }

    // ==================== Builder ====================

    public static class Builder {
        private Agent agent;
        private JimiRuntime jimiRuntime;
        private Context context;
        private Wire wire;
        private ToolRegistry toolRegistry;
        private Compaction compaction;

        private ContextManager contextManager;
        private MemoryManager memoryManager;
        private ToolErrorTracker toolErrorTracker;
        private HookRegistry hookRegistry;
        private boolean isSubagent = false;

        public Builder agent(Agent agent) { this.agent = agent; return this; }
        public Builder runtime(JimiRuntime runtime) { this.jimiRuntime = runtime; return this; }
        public Builder context(Context context) { this.context = context; return this; }
        public Builder wire(Wire wire) { this.wire = wire; return this; }
        public Builder toolRegistry(ToolRegistry toolRegistry) { this.toolRegistry = toolRegistry; return this; }
        public Builder compaction(Compaction compaction) { this.compaction = compaction; return this; }
        public Builder contextManager(ContextManager contextManager) { this.contextManager = contextManager; return this; }
        public Builder memoryManager(MemoryManager memoryManager) { this.memoryManager = memoryManager; return this; }
        public Builder toolErrorTracker(ToolErrorTracker tracker) { this.toolErrorTracker = tracker; return this; }
        public Builder hookRegistry(HookRegistry hookRegistry) { this.hookRegistry = hookRegistry; return this; }
        public Builder isSubagent(boolean isSubagent) { this.isSubagent = isSubagent; return this; }

        public AgentExecutor build() { return new AgentExecutor(this); }
    }
}
