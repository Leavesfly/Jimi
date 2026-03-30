package io.leavesfly.jimi.core.engine;

import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.core.engine.toolcall.ToolDispatcher;
import io.leavesfly.jimi.core.session.Session;
import io.leavesfly.jimi.exception.MaxStepsReachedException;
import io.leavesfly.jimi.exception.RunCancelledException;
import io.leavesfly.jimi.llm.ChatCompletionChunk;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.message.Message;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ReAct 核心循环
 * <p>
 * 职责：执行纯净的 ReAct 循环（LLM 调用 -> 工具执行 -> 循环）
 * <p>
 * 设计原则：
 * - 单一职责：仅负责 ReAct 循环的核心逻辑
 * - 回调机制：通过回调让编排层注入附加行为（Wire 事件、Token 统计等）
 * - 零附加功能：不包含 Hook、Memory、上下文压缩等逻辑
 */
@Slf4j
public class ReactLoop {

    private final LLM llm;
    private final ToolDispatcher toolDispatcher;
    private final Session session;
    private final int maxSteps;

    // ==================== 回调接口（编排层通过这些注入附加行为）====================

    /** 步骤开始回调：(localStepNo, globalStepNo) -> void */
    @Setter
    private BiConsumer<Integer, Integer> onStepBegin;

    /** 流式内容回调：chunk -> void（用于实时输出到 Wire） */
    @Setter
    private Consumer<ChatCompletionChunk> onContentChunk;

    /** Assistant 消息完成回调：(message, accumulator) -> void（用于 Token 统计等） */
    @Setter
    private BiConsumer<Message, StreamAccumulator> onAssistantMessage;

    /** 步骤前检查回调：返回 Mono<Void>（用于上下文压缩等） */
    @Setter
    private Function<Integer, Mono<Void>> beforeStep;

    public ReactLoop(LLM llm, ToolDispatcher toolDispatcher, Session session, int maxSteps) {
        this.llm = llm;
        this.toolDispatcher = toolDispatcher;
        this.session = session;
        this.maxSteps = maxSteps;
    }

    /**
     * 执行 ReAct 循环
     *
     * @param context      上下文
     * @param systemPrompt 系统提示词
     * @param toolSchemas  工具 schema 列表
     * @return 完成的 Mono
     */
    public Mono<Void> run(Context context, String systemPrompt, List<Object> toolSchemas) {
        return Mono.defer(() -> step(context, systemPrompt, toolSchemas, 1));
    }

    /**
     * 执行单步循环
     */
    private Mono<Void> step(Context context, String systemPrompt, List<Object> toolSchemas, int localStepNo) {
        // === 1. 取消检查 ===
        if (session.isCancelled()) {
            log.info("Loop cancelled at step {}", localStepNo);
            return Mono.error(new RunCancelledException());
        }

        // === 2. 全局步数检查 ===
        int globalStepNo = session.incrementAndGetGlobalStep();
        if (globalStepNo > maxSteps) {
            return Mono.error(new MaxStepsReachedException(maxSteps));
        }

        // === 3. 通知步骤开始 ===
        if (onStepBegin != null) {
            onStepBegin.accept(localStepNo, globalStepNo);
        }
        log.info("ReAct loop step {}/{}", globalStepNo, maxSteps);

        // === 4. 步骤前检查（上下文压缩等）===
        Mono<Void> beforeStepMono = beforeStep != null ? beforeStep.apply(globalStepNo) : Mono.empty();

        // === 5. 执行核心逻辑 ===
        return beforeStepMono
                .then(llmCallAndProcess(context, systemPrompt, toolSchemas))
                .flatMap(finished -> {
                    if (finished) {
                        log.info("ReAct loop finished at step {}", globalStepNo);
                        return Mono.empty();
                    }
                    return step(context, systemPrompt, toolSchemas, localStepNo + 1);
                });
    }

    /**
     * LLM 调用并处理响应
     *
     * @return true 表示循环结束，false 表示需要继续
     */
    private Mono<Boolean> llmCallAndProcess(Context context, String systemPrompt, List<Object> toolSchemas) {
        return llm.getChatProvider()
                .generateStream(systemPrompt, context.getHistory(), toolSchemas)
                // 累积流式响应，同时触发内容回调
                .reduce(new StreamAccumulator(), (acc, chunk) -> {
                    if (onContentChunk != null && isContentChunk(chunk)) {
                        onContentChunk.accept(chunk);
                    }
                    return acc.accumulate(chunk);
                })
                // 构建消息并处理
                .flatMap(acc -> processAccumulator(acc, context))
                .onErrorResume(this::handleLLMError);
    }

    private boolean isContentChunk(ChatCompletionChunk chunk) {
        return chunk.getType() == ChatCompletionChunk.ChunkType.CONTENT
                || chunk.getType() == ChatCompletionChunk.ChunkType.REASONING;
    }

    /**
     * 处理累加器：构建消息、保存、执行工具
     */
    private Mono<Boolean> processAccumulator(StreamAccumulator acc, Context context) {
        Message assistantMessage = acc.toAssistantMessage();

        // 触发回调
        if (onAssistantMessage != null) {
            onAssistantMessage.accept(assistantMessage, acc);
        }

        // 保存消息到上下文
        return context.appendMessage(assistantMessage)
                .then(processToolCalls(assistantMessage, context));
    }

    /**
     * 处理工具调用
     *
     * @return true 表示循环结束，false 表示需要继续
     */
    private Mono<Boolean> processToolCalls(Message assistantMessage, Context context) {
        List<?> toolCalls = assistantMessage.getToolCalls();

        // 没有工具调用，循环结束
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("No tool calls, loop finished");
            return Mono.just(true);
        }

        log.info("Executing {} tool calls", toolCalls.size());

        // 执行工具调用
        return toolDispatcher.executeToolCalls(assistantMessage.getToolCalls(), context)
                .then(Mono.defer(() -> {
                    // 检查是否需要终止（连续重复错误）
                    if (toolDispatcher.shouldTerminateLoop()) {
                        log.warn("Tool error threshold reached, terminating loop");
                        return Mono.just(true);
                    }
                    return Mono.just(false);
                }));
    }

    /**
     * 处理 LLM 错误
     */
    private Mono<Boolean> handleLLMError(Throwable e) {
        log.debug("LLM call failed: {}", e.getMessage());
        return Mono.just(true);
    }
}
