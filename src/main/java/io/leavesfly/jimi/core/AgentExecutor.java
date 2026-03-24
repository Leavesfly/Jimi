package io.leavesfly.jimi.core;

import io.leavesfly.jimi.core.agent.Agent;
import io.leavesfly.jimi.core.compaction.Compaction;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.core.engine.executor.*;
import io.leavesfly.jimi.core.engine.hook.HookContext;
import io.leavesfly.jimi.core.engine.hook.HookRegistry;
import io.leavesfly.jimi.core.engine.hook.HookType;
import io.leavesfly.jimi.core.engine.runtime.Runtime;
import io.leavesfly.jimi.exception.MaxStepsReachedException;
import io.leavesfly.jimi.exception.RunCancelledException;

import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.tool.ToolRegistry;

import io.leavesfly.jimi.util.SpringContextUtils;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.message.*;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import java.util.Objects;

/**
 * Agent 执行器
 * <p>
 * 职责：
 * - Agent 主循环调度
 * - 协调各组件完成执行流程
 * <p>
 * 设计原则：
 * - 单一职责：仅负责执行流程协调
 * - 组合优于继承：通过组合组件完成功能
 * - Builder 模式：简化构造过程
 */
@Slf4j
public class AgentExecutor {

    // ==================== 核心依赖（必需） ====================
    private final String agentName;
    private final Agent agent;
    private final Runtime runtime;
    private final Context context;
    private final Wire wire;
    private final ToolRegistry toolRegistry;
    private final Compaction compaction;

    // ==================== 拆分组件 ====================
    private final ExecutionState executionState;
    private final MemoryRecorder memoryRecorder;
    private final ResponseProcessor responseProcessor;
    private final ContextManager contextManager;
    private final HookRegistry hookRegistry;

    // ==================== 可选配置 ====================
    private final boolean isSubagent;


    /**
     * 私有构造函数，通过 Builder 创建实例
     */
    private AgentExecutor(Builder builder) {
        // 必需参数
        this.agent = Objects.requireNonNull(builder.agent, "agent is required");
        this.runtime = Objects.requireNonNull(builder.runtime, "runtime is required");
        this.context = Objects.requireNonNull(builder.context, "context is required");
        this.wire = Objects.requireNonNull(builder.wire, "wire is required");
        this.toolRegistry = Objects.requireNonNull(builder.toolRegistry, "toolRegistry is required");
        this.compaction = Objects.requireNonNull(builder.compaction, "compaction is required");

        // 可选参数
        this.isSubagent = builder.isSubagent;
        this.agentName = agent.getName();

        // 初始化拆分组件（通过 SpringContextUtils 从容器获取原型 Bean）
        this.executionState = new ExecutionState();

        // 从 Spring 容器获取原型 Bean，然后设置依赖参数
        this.memoryRecorder = SpringContextUtils.getBean(MemoryRecorder.class);
        this.responseProcessor = SpringContextUtils.getBean(ResponseProcessor.class);
        this.contextManager = SpringContextUtils.getBean(ContextManager.class);
        this.hookRegistry = SpringContextUtils.getBean(HookRegistry.class);
    }


    /**
     * 创建 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * AgentExecutor Builder
     * <p>
     * 使用示例：
     * <pre>
     * AgentExecutor executor = AgentExecutor.builder()
     *     .agent(agent)
     *     .runtime(runtime)
     *     .context(context)
     *     .wire(wire)
     *     .toolRegistry(toolRegistry)
     *     .compaction(compaction)
     *     // 可选配置
     *     .skillComponents(SkillComponents.of(matcher, provider))
     *     .memoryComponents(MemoryComponents.of(config, injector, extractor))
     *     .build();
     * </pre>
     */
    public static class Builder {
        // 必需参数
        private Agent agent;
        private Runtime runtime;
        private Context context;
        private Wire wire;
        private ToolRegistry toolRegistry;
        private Compaction compaction;

        // 可选参数
        private boolean isSubagent = false;

        private Builder() {
        }

        // ==================== 必需参数 ====================

        public Builder agent(Agent agent) {
            this.agent = agent;
            return this;
        }

        public Builder runtime(Runtime runtime) {
            this.runtime = runtime;
            return this;
        }

        public Builder context(Context context) {
            this.context = context;
            return this;
        }

        public Builder wire(Wire wire) {
            this.wire = wire;
            return this;
        }

        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        public Builder compaction(Compaction compaction) {
            this.compaction = compaction;
            return this;
        }

        // ==================== 可选参数 ====================

        public Builder isSubagent(boolean isSubagent) {
            this.isSubagent = isSubagent;
            return this;
        }


        /**
         * 构建 AgentExecutor 实例
         */
        public AgentExecutor build() {
            return new AgentExecutor(this);
        }
    }


    // ==================== 执行方法 ====================

    /**
     * 执行 Agent 任务
     *
     * @param userInput 用户输入
     * @return 执行完成的 Mono
     */
    public Mono<Void> execute(List<ContentPart> userInput) {
        return execute(userInput, false);
    }

    /**
     * 执行 Agent 任务
     *
     * @param userInput     用户输入
     * @param skipKnowledge 是否跳过知识检索和 Skill 匹配（用于系统内部续问场景）
     * @return 执行完成的 Mono
     */
    public Mono<Void> execute(List<ContentPart> userInput, boolean skipKnowledge) {
        return Mono.defer(() -> {

            // 初始化任务跟踪
            executionState.initializeTask();

            // 创建用户消息
            Message userMessage = Message.user(userInput);

            // 估算用户输入的 Token 数
            int userInputTokens = responseProcessor.estimateTokensFromMessage(userMessage);
            int newTokenCount = context.getTokenCount() + userInputTokens;
            executionState.addTokens(userInputTokens);

            // 提取用户查询文本
            String userQuery = memoryRecorder.extractHighLevelIntent(userInput);
            executionState.setCurrentUserQuery(userQuery);

            // 构建用户输入文本（用于 Hook 上下文）
            String userInputText = extractUserInputText(userInput);

            // 触发 PRE_USER_INPUT hook
            HookContext preInputHookContext = HookContext.builder()
                    .hookType(HookType.PRE_USER_INPUT)
                    .workDir(runtime.getWorkDir())
                    .userInput(userInputText)
                    .agentName(agentName)
                    .build();

            // 创建检查点 0，添加用户消息（Context 内部自动提取高层意图），并更新 Token 计数
            return triggerHookSafely(HookType.PRE_USER_INPUT, preInputHookContext)
                    .then(context.checkpoint(false))
                    .then(context.appendMessage(userMessage))
                    .then(context.updateTokenCount(newTokenCount))
                    .doOnSuccess(v -> log.debug("Added user input: {} tokens (total: {})", userInputTokens, newTokenCount))
                    // Agent 主循环
                    .then(agentLoop(skipKnowledge))

                    .doOnSuccess(v -> {
                        log.info("Agent execution completed");
                        memoryRecorder.recordTaskHistory(executionState, context, "success").subscribe();
                        executionState.incrementTasksCompleted();

                        // 触发 POST_USER_INPUT hook（异步，不阻塞主流程）
                        HookContext postInputHookContext = HookContext.builder()
                                .hookType(HookType.POST_USER_INPUT)
                                .workDir(runtime.getWorkDir())
                                .userInput(userInputText)
                                .agentName(agentName)
                                .build();
                        triggerHookSafely(HookType.POST_USER_INPUT, postInputHookContext).subscribe();
                    })
                    .doOnError(e -> {
                        log.error("Agent execution failed", e);
                        memoryRecorder.recordTaskHistory(executionState, context, "failed").subscribe();

                        // 触发 ON_ERROR hook（异步，不阻塞主流程）
                        HookContext errorHookContext = HookContext.builder()
                                .hookType(HookType.ON_ERROR)
                                .workDir(runtime.getWorkDir())
                                .errorMessage(e.getMessage())
                                .errorStackTrace(getStackTraceString(e))
                                .agentName(agentName)
                                .build();
                        triggerHookSafely(HookType.ON_ERROR, errorHookContext).subscribe();
                    });
        });
    }

    /**
     * 从用户输入内容中提取文本
     */
    private String extractUserInputText(List<ContentPart> userInput) {
        return userInput.stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).getText())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }

    /**
     * 获取异常堆栈字符串
     */
    private String getStackTraceString(Throwable throwable) {
        java.io.StringWriter stringWriter = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(stringWriter));
        return stringWriter.toString();
    }

    /**
     * 安全触发 Hook，捕获异常避免影响主流程
     */
    private Mono<Void> triggerHookSafely(HookType hookType, HookContext hookContext) {
        try {
            return hookRegistry.trigger(hookType, hookContext)
                    .onErrorResume(e -> {
                        log.warn("Hook trigger failed for type {}: {}", hookType, e.getMessage());
                        return Mono.empty();
                    });
        } catch (Exception e) {
            log.warn("Hook trigger failed for type {}: {}", hookType, e.getMessage());
            return Mono.empty();
        }
    }

    /**
     * Agent 主循环
     */
    private Mono<Void> agentLoop(boolean skipKnowledge) {
        return Mono.defer(() -> agentLoopStep(1, skipKnowledge));
    }

    /**
     * Agent 循环步骤
     *
     * @param stepNo        当前步骤号
     * @param skipKnowledge 是否跳过知识检索和 Skill 匹配
     */
    private Mono<Void> agentLoopStep(int stepNo, boolean skipKnowledge) {
        // 检查是否已取消
        if (runtime.getSession().isCancelled()) {
            log.info("Agent '{}' cancelled at step {}", agentName != null ? agentName : "main", stepNo);
            wire.send(new StepInterrupted());
            return Mono.error(new RunCancelledException());
        }
        
        // 记录步数
        executionState.setStepsInTask(stepNo);

        // 获取并递增全局步数
        int globalStepNo = runtime.getSession().incrementAndGetGlobalStep();

        // 检查全局最大步数
        int maxSteps = runtime.getConfig().getLoopControl().getMaxStepsPerRun();
        if (globalStepNo > maxSteps) {
            return Mono.error(new MaxStepsReachedException(maxSteps));
        }

        // 发送步骤开始消息
        wire.send(new StepBegin(globalStepNo, isSubagent, agentName));

        log.debug("Agent '{}' local step {}, global step {}/{}",
                agentName != null ? agentName : "main", stepNo, globalStepNo, maxSteps);

        return Mono.defer(() -> {
            // 检查上下文是否超限，触发压缩
            Mono<Void> pipeline = contextManager.checkAndCompact(context, runtime.getLlm(), compaction)
                    .then(context.checkpoint(false))
                    .then();

            // 仅在非跳过模式下注入 Skills 和知识
            if (!skipKnowledge) {
                pipeline = pipeline
                        .then(contextManager.matchAndInjectSkills(context, stepNo))
                        .then(contextManager.matchAndInjectKnowlwdge(context, stepNo));
            }

            return pipeline
                    .then(step())
                    .flatMap(finished -> {
                        if (finished) {
                            log.info("Agent '{}' loop finished at local step {}, global step {}",
                                    agentName != null ? agentName : "main", stepNo, globalStepNo);
                            return Mono.empty();
                        } else {
                            // 后续步骤中 skipKnowledge 不再生效（stepNo > 1 时 ContextManager 本身就会跳过）
                            return agentLoopStep(stepNo + 1, skipKnowledge);
                        }
                    })
                    .then()
                    .onErrorResume(e -> {
                        log.error("Error in Agent '{}' at local step {}, global step {}",
                                agentName != null ? agentName : "main", stepNo, globalStepNo, e);
                        wire.send(new StepInterrupted());
                        return Mono.error(e);
                    });
        });
    }

    /**
     * 执行单步
     */
    private Mono<Boolean> step() {
        return Mono.defer(() -> {
            LLM llm = runtime.getLlm();
            List<Object> toolSchemas = new ArrayList<>(toolRegistry.getToolSchemas(agent.getTools()));

            String systemPrompt = agent.getSystemPrompt();

            return llm.getChatProvider()
                    .generateStream(systemPrompt, context.getHistory(), toolSchemas)
                    .contextWrite(ctx -> ctx.put("workDir", runtime.getWorkDir()))
                    .reduce(new ResponseProcessor.StreamAccumulator(), responseProcessor::processStreamChunk)
                    .flatMap(acc -> responseProcessor.handleStreamCompletion(acc, context, executionState, toolRegistry, runtime.getWorkDir()))
                    .onErrorResume(responseProcessor::handleLLMError);
        });
    }

    // ==================== Getter 方法 ====================

    public Agent getAgent() {
        return agent;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public Context getContext() {
        return context;
    }

    public Wire getWire() {
        return wire;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public boolean isSubagent() {
        return isSubagent;
    }

    public Compaction getCompaction() {
        return compaction;
    }
}