package io.leavesfly.jimi.core.engine.executor;


import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.core.engine.hook.HookContext;
import io.leavesfly.jimi.core.engine.hook.HookRegistry;
import io.leavesfly.jimi.core.engine.hook.HookType;

import io.leavesfly.jimi.core.engine.toolcall.ToolErrorTracker;

import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.ui.DebugLogger;
import io.leavesfly.jimi.tool.Tool;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.util.SpringContextUtils;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.message.ToolCallMessage;
import io.leavesfly.jimi.wire.message.ToolResultMessage;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 工具调度器
 * <p>
 * 职责：
 * - 工具调用验证
 * - 工具调用执行（支持并行 + 串行混合调度）
 * - 工具错误跟踪
 * - 工具结果格式化
 * <p>
 * 并行策略：
 * - 标记为 isConcurrentSafe() 的工具（如读取、搜索）可以并行执行
 * - 非并发安全的工具（如文件写入、Bash）串行执行
 * - 工具调用按原始顺序分组：连续的并发安全工具合并为一个并行批次，
 *   遇到非并发安全工具则单独串行执行
 */
@Slf4j
public class ToolDispatcher {

    private final ToolRegistry toolRegistry;
    private final Wire wire;

    private final ToolErrorTracker toolErrorTracker;
    private final HookRegistry hookRegistry;
    private final Path workDir;

    /**
     * 基础构造函数
     */
    public ToolDispatcher(ToolRegistry toolRegistry) {
        this(toolRegistry, null);
    }

    /**
     * 带工作目录的构造函数（支持 Hook 触发）
     */
    public ToolDispatcher(ToolRegistry toolRegistry, Path workDir) {
        this.toolRegistry = toolRegistry;
        this.workDir = workDir;
        wire = SpringContextUtils.getBean(Wire.class);

        toolErrorTracker = SpringContextUtils.getBean(ToolErrorTracker.class);
        this.hookRegistry = SpringContextUtils.getBean(HookRegistry.class);
    }


    private static final int MAX_PARALLEL_CONCURRENCY = 4;

    /**
     * 执行工具调用列表（并行 + 串行混合调度）
     * <p>
     * 策略：将工具调用按顺序分组为"批次"（batch），
     * 连续的并发安全工具合并为一个并行批次，非并发安全工具单独为一个串行批次。
     * 批次之间严格按顺序执行，批次内部并行执行。
     *
     * @param toolCalls 工具调用列表
     * @param context   上下文
     * @return 完成的 Mono
     */
    public Mono<Void> executeToolCalls(List<ToolCall> toolCalls, Context context) {
        List<ToolCallBatch> batches = groupIntoBatches(toolCalls);
        log.info("Grouped {} tool calls into {} batches for execution", toolCalls.size(), batches.size());

        // 按批次顺序执行，每个批次内部根据类型决定并行或串行
        return Flux.fromIterable(batches)
                .concatMap(batch -> executeBatch(batch, context))
                .collectList()
                .flatMap(allResults -> {
                    // 将所有批次的结果展平
                    List<Message> flatResults = new ArrayList<>();
                    for (List<Message> batchResult : allResults) {
                        flatResults.addAll(batchResult);
                    }
                    log.info("Collected {} tool results after mixed execution", flatResults.size());
                    return context.appendMessage(flatResults)
                            .doOnSuccess(v -> log.info("Successfully appended {} tool results to context", flatResults.size()))
                            .doOnError(e -> log.error("Failed to append tool results to context", e));
                });
    }

    /**
     * 将工具调用按并发安全性分组为批次
     * <p>
     * 连续的并发安全工具合并为一个并行批次，非并发安全工具单独为一个串行批次。
     * 保持原始顺序不变。
     */
    private List<ToolCallBatch> groupIntoBatches(List<ToolCall> toolCalls) {
        List<ToolCallBatch> batches = new ArrayList<>();
        List<ToolCall> currentParallelGroup = new ArrayList<>();

        for (ToolCall toolCall : toolCalls) {
            boolean concurrentSafe = isToolConcurrentSafe(toolCall);

            if (concurrentSafe) {
                // 并发安全的工具，累积到当前并行组
                currentParallelGroup.add(toolCall);
            } else {
                // 遇到非并发安全的工具，先提交之前累积的并行组
                if (!currentParallelGroup.isEmpty()) {
                    batches.add(new ToolCallBatch(new ArrayList<>(currentParallelGroup), true));
                    currentParallelGroup.clear();
                }
                // 非并发安全工具单独为一个串行批次
                batches.add(new ToolCallBatch(List.of(toolCall), false));
            }
        }

        // 提交最后一个并行组
        if (!currentParallelGroup.isEmpty()) {
            batches.add(new ToolCallBatch(currentParallelGroup, true));
        }

        return batches;
    }

    /**
     * 判断工具调用是否并发安全
     */
    private boolean isToolConcurrentSafe(ToolCall toolCall) {
        String toolName = toolCall.getFunction().getName();
        Optional<Tool<?>> toolOpt = toolRegistry.getTool(toolName);
        return toolOpt.map(Tool::isConcurrentSafe).orElse(false);
    }

    /**
     * 执行一个批次的工具调用
     */
    private Mono<List<Message>> executeBatch(ToolCallBatch batch, Context context) {
        if (batch.parallel && batch.toolCalls.size() > 1) {
            log.info("Executing parallel batch of {} concurrent-safe tool calls", batch.toolCalls.size());
            return Flux.fromIterable(batch.toolCalls)
                    .flatMap(toolCall -> executeToolCallSafely(toolCall, context)
                                    .subscribeOn(Schedulers.boundedElastic()),
                            MAX_PARALLEL_CONCURRENCY)
                    .collectList();
        } else {
            // 串行执行（单个非并发安全工具，或单个并发安全工具）
            String toolName = batch.toolCalls.get(0).getFunction().getName();
            log.info("Executing serial batch: {}", toolName);
            return Flux.fromIterable(batch.toolCalls)
                    .concatMap(toolCall -> executeToolCallSafely(toolCall, context))
                    .collectList();
        }
    }

    /**
     * 安全执行单个工具调用（带错误恢复）
     */
    private Mono<Message> executeToolCallSafely(ToolCall toolCall, Context context) {
        return executeToolCall(toolCall, context)
                .doOnError(e -> log.error("Tool call failed: {}", toolCall.getFunction().getName(), e))
                .onErrorResume(e -> {
                    String toolCallId = (toolCall != null && toolCall.getId() != null)
                            ? toolCall.getId() : "unknown";
                    return Mono.just(Message.tool(toolCallId,
                            "Tool execution failed: " + e.getMessage()));
                });
    }

    /**
     * 工具调用批次
     */
    private record ToolCallBatch(List<ToolCall> toolCalls, boolean parallel) {
    }

    /**
     * 执行单个工具调用
     *
     * @param toolCall 工具调用
     * @param context  上下文
     * @return 工具结果消息
     */
    public Mono<Message> executeToolCall(ToolCall toolCall, Context context) {
        return Mono.defer(() -> {
            try {


                // 发送工具调用开始消息到 Wire
                wire.send(new ToolCallMessage(toolCall));

                String toolName = toolCall.getFunction().getName();
                String toolCallId = toolCall.getId();
                String rawArgs = toolCall.getFunction().getArguments();
                String toolSignature = toolName + ":" + rawArgs;

                // Debug: 记录工具执行信息
                DebugLogger.logToolExecution(toolName, rawArgs);
                long toolStartTime = System.currentTimeMillis();

                return executeValidToolCall(toolName, rawArgs, toolCallId, toolSignature, context)
                        .doOnNext(msg -> {
                            long elapsed = System.currentTimeMillis() - toolStartTime;
                            String content = msg.getTextContent();
                            int resultSize = content != null ? content.length() : 0;
                            DebugLogger.logToolResult(toolName, elapsed, resultSize);
                        });
            } catch (Exception e) {
                log.error("Unexpected error in executeToolCall", e);
                String errorToolCallId = (toolCall != null && toolCall.getId() != null) ? toolCall.getId() : "unknown";
                ToolResult errorResult = ToolResult.error("Internal error: " + e.getMessage(), "Execution error");
                wire.send(new ToolResultMessage(errorToolCallId, errorResult));
                return Mono.just(Message.tool(errorToolCallId, "Internal error executing tool: " + e.getMessage()));
            }
        });
    }

    /**
     * 执行已验证的工具调用
     */
    private Mono<Message> executeValidToolCall(String toolName, String arguments, String toolCallId, String toolSignature, Context context) {

        // 触发 PRE_TOOL_CALL hook
        HookContext preHookContext = HookContext.builder()
                .hookType(HookType.PRE_TOOL_CALL)
                .workDir(workDir)
                .toolName(toolName)
                .toolCallId(toolCallId)
                .build();

        return triggerHookSafely(HookType.PRE_TOOL_CALL, preHookContext)
                .then(toolRegistry.execute(toolName, arguments))
                .doOnNext(result -> {
                    // 发送工具执行结果消息到 Wire
                    wire.send(new ToolResultMessage(toolCallId, result));

                    // 触发 POST_TOOL_CALL hook（异步，不阻塞主流程）
                    HookContext postHookContext = HookContext.builder()
                            .hookType(HookType.POST_TOOL_CALL)
                            .workDir(workDir)
                            .toolName(toolName)
                            .toolCallId(toolCallId)
                            .toolResult(formatToolResult(result))
                            .build();
                    triggerHookSafely(HookType.POST_TOOL_CALL, postHookContext).subscribe();
                })
                .map(result -> convertToolResultToMessage(result, toolCallId, toolSignature, context))
                .onErrorResume(e -> {
                    log.error("Tool execution failed: {}", toolName, e);
                    ToolResult errorResult = ToolResult.error("Tool execution error: " + e.getMessage(), "Execution failed");
                    wire.send(new ToolResultMessage(toolCallId, errorResult));

                    return Mono.just(Message.tool(toolCallId, "Tool execution error: " + e.getMessage()));
                });
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
     * 将工具结果转换为消息
     */
    private Message convertToolResultToMessage(ToolResult result, String toolCallId, String toolSignature, Context context) {

        String content;

        if (result.isOk()) {
            toolErrorTracker.clearErrors();
            content = formatToolResult(result);

        } else if (result.isError()) {
            toolErrorTracker.trackError(toolSignature);
            content = toolErrorTracker.buildErrorContent(result.getMessage(), result.getOutput(), toolSignature);
        } else {
            content = result.getMessage();
        }

        return Message.tool(toolCallId, content);
    }

    /**
     * 格式化工具结果
     */
    private String formatToolResult(ToolResult result) {
        StringBuilder sb = new StringBuilder();

        if (!result.getOutput().isEmpty()) {
            sb.append(result.getOutput());
        }

        if (!result.getMessage().isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(result.getMessage());
        }

        return sb.toString();
    }

    /**
     * 检查是否应该终止循环（因为连续重复错误）
     *
     * @return true 如果应该终止
     */
    public boolean shouldTerminateLoop() {
        return toolErrorTracker.shouldTerminateLoop();
    }

    /**
     * 清除错误跟踪状态
     */
    public void clearErrors() {
        toolErrorTracker.clearErrors();
    }
}
