package io.leavesfly.jimi.core.engine.toolcall;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.config.info.ToolOutputConfig;
import io.leavesfly.jimi.core.hook.HookContext;
import io.leavesfly.jimi.core.hook.HookExecutor;
import io.leavesfly.jimi.core.hook.HookRegistry;
import io.leavesfly.jimi.core.hook.HookType;

import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.ui.DebugLogger;
import io.leavesfly.jimi.tool.Tool;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.message.ToolCallMessage;
import io.leavesfly.jimi.wire.message.ToolResultMessage;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * - 非并发安全的工具（如文件写入、BashTool）串行执行
 * - 工具调用按原始顺序分组：连续的并发安全工具合并为一个并行批次，
 *   遇到非并发安全工具则单独串行执行
 * <p>
 * 大输出策略：
 * - 超过阈值的成功输出会落盘到 {@code .jimi/tool-output/}，上下文仅保留摘要与文件路径
 */
@Slf4j
public class ToolDispatcher {

    private static final ObjectMapper HOOK_INPUT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> HOOK_INPUT_TYPE = new TypeReference<>() {};

    private final ToolRegistry toolRegistry;
    private final Wire wire;
    private final ToolErrorTracker toolErrorTracker;
    private final Path workDir;
    private final HookRegistry hookRegistry;
    private final ToolOutputConfig toolOutputConfig;
    private final String sessionId;

    public ToolDispatcher(ToolRegistry toolRegistry, Path workDir, Wire wire,
                          ToolErrorTracker toolErrorTracker, HookRegistry hookRegistry) {
        this(toolRegistry, workDir, wire, toolErrorTracker, hookRegistry, new ToolOutputConfig(), "default");
    }

    public ToolDispatcher(ToolRegistry toolRegistry, Path workDir, Wire wire,
                          ToolErrorTracker toolErrorTracker, HookRegistry hookRegistry,
                          ToolOutputConfig toolOutputConfig, String sessionId) {
        this.toolRegistry = toolRegistry;
        this.workDir = workDir;
        this.wire = wire;
        this.toolErrorTracker = toolErrorTracker;
        this.hookRegistry = hookRegistry;
        this.toolOutputConfig = toolOutputConfig != null ? toolOutputConfig : new ToolOutputConfig();
        this.sessionId = sessionId != null ? sessionId : "default";
    }


    private static final int MAX_PARALLEL_CONCURRENCY = 4;

    /**
     * 执行工具调用列表（并行 + 串行混合调度）
     * <p>
     * 执行流程：
     * 1. 按并发安全性分组（连续的读操作可并行，写操作串行）
     * 2. 按批次顺序执行（批次间串行，批次内可并行）
     * 3. 收集所有结果并追加到上下文
     *
     * @param toolCalls 工具调用列表
     * @param context   上下文
     * @return 完成的 Mono
     */
    public Mono<Void> executeToolCalls(List<ToolCall> toolCalls, Context context) {
        // === 1. 按并发安全性分组 ===
        List<ToolCallBatch> batches = groupIntoBatches(toolCalls);
        log.info("Grouped {} tool calls into {} batches for execution", toolCalls.size(), batches.size());

        // === 2. 按批次顺序执行（批次间串行，批次内并行） ===
        return Flux.fromIterable(batches)
                .concatMap(batch -> executeBatch(batch, context))
                // === 3. 收集所有结果并追加到上下文 ===
                .collectList()
                .flatMap(allResults -> appendAllResults(allResults, context));
    }

    /**
     * 将所有批次的执行结果追加到上下文
     *
     * @param allResults 所有批次的结果列表（嵌套列表）
     * @param context    上下文
     * @return 完成的 Mono
     */
    private Mono<Void> appendAllResults(List<List<Message>> allResults, Context context) {
        // 展平嵌套列表
        List<Message> flatResults = allResults.stream()
                .flatMap(List::stream)
                .toList();

        log.info("Collected {} tool results after mixed execution", flatResults.size());

        return context.appendMessage(flatResults)
                .doOnSuccess(v -> log.info("Successfully appended {} tool results to context", flatResults.size()))
                .doOnError(e -> log.error("Failed to append tool results to context", e));
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
                    // flatMapSequential：并发执行但按原始顺序发射，保证工具结果与 tool_calls 顺序一致
                    .flatMapSequential(toolCall -> executeToolCallSafely(toolCall, context)
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
     * 执行已验证的工具调用（集成 Hooks 机制）
     *
     * 执行流程：
     * 1. 触发 PRE_TOOL_USE hook（可阻塞工具调用）
     * 2. 执行工具
     * 3. 触发 POST_TOOL_USE hook（成功时）或 POST_TOOL_USE_FAILURE hook（失败时）
     */
    private Mono<Message> executeValidToolCall(String toolName, String arguments, String toolCallId, String toolSignature, Context context) {
        Map<String, Object> toolInput = parseHookToolInput(arguments);

        // 构建 PRE_TOOL_USE hook 上下文
        HookContext preHookContext = HookContext.builder()
                .hookType(HookType.PRE_TOOL_USE)
                .sessionId(sessionId)
                .workDir(workDir)
                .toolName(toolName)
                .toolCallId(toolCallId)
                .toolInput(toolInput)
                .build();

        return triggerPreHookSafely(preHookContext)
                .defaultIfEmpty("")
                .flatMap(blockReason -> {
                    if (!blockReason.isBlank()) {
                        // PRE_TOOL_USE hook 阻塞了本次工具调用（对齐 Claude Code exit code 2 语义）
                        log.warn("Tool call blocked by PRE_TOOL_USE hook: {} ({})", toolName, blockReason);
                        ToolResult blockedResult = ToolResult.error(
                                "Tool call blocked by hook: " + blockReason, "Blocked by hook");
                        wire.send(new ToolResultMessage(toolCallId, blockedResult));
                        return Mono.just(Message.tool(toolCallId,
                                "Tool call was blocked by a PRE_TOOL_USE hook: " + blockReason));
                    }
                    return toolRegistry.execute(toolName, arguments)
                            .flatMap(result -> {
                                // 触发 POST_TOOL_USE hook（异步，不阻塞主流程）
                                HookContext postHookContext = HookContext.builder()
                                        .hookType(HookType.POST_TOOL_USE)
                                        .sessionId(sessionId)
                                        .workDir(workDir)
                                        .toolName(toolName)
                                        .toolCallId(toolCallId)
                                        .toolInput(toolInput)
                                        .toolResult(formatToolResult(result))
                                        .build();
                                triggerHookSafely(HookType.POST_TOOL_USE, postHookContext).subscribe();

                                return processToolResult(result, toolName, toolCallId, toolSignature, context);
                            });
                })
                .onErrorResume(e -> {
                    // 触发 POST_TOOL_USE_FAILURE hook（异步）
                    HookContext failureHookContext = HookContext.builder()
                            .hookType(HookType.POST_TOOL_USE_FAILURE)
                            .sessionId(sessionId)
                            .workDir(workDir)
                            .toolName(toolName)
                            .toolCallId(toolCallId)
                            .toolInput(toolInput)
                            .errorMessage(e.getMessage())
                            .build();
                    triggerHookSafely(HookType.POST_TOOL_USE_FAILURE, failureHookContext).subscribe();

                    return handleToolError(e, toolName, toolCallId);
                });
    }

    private Map<String, Object> parseHookToolInput(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return HOOK_INPUT_MAPPER.readValue(arguments, HOOK_INPUT_TYPE);
        } catch (Exception e) {
            log.debug("Unable to parse tool arguments for hook context: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 安全触发 Hook（不影响主流程）
     */
    private Mono<Void> triggerHookSafely(HookType type, HookContext context) {
        if (hookRegistry == null) {
            return Mono.empty();
        }
        return hookRegistry.trigger(type, context)
                .onErrorResume(e -> {
                    log.warn("Hook trigger failed for {}: {}", type, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 触发 PRE_TOOL_USE hook 并返回阻塞原因
     *
     * @return 阻塞原因；未被阻塞时发射 empty
     */
    private Mono<String> triggerPreHookSafely(HookContext context) {
        if (hookRegistry == null) {
            return Mono.empty();
        }
        return hookRegistry.triggerWithResults(HookType.PRE_TOOL_USE, context)
                .flatMapIterable(results -> results)
                .filter(HookExecutor.HookResult::isBlocked)
                .next()
                .map(blocked -> blocked.getReason() != null && !blocked.getReason().isBlank()
                        ? blocked.getReason() : "Blocked by hook")
                .onErrorResume(e -> {
                    log.warn("Hook trigger failed for PRE_TOOL_USE: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 处理工具执行结果
     * <p>
     * - 发送结果到 Wire
     * - 将结果转换为上下文消息
     */
    private Mono<Message> processToolResult(ToolResult result, String toolName, String toolCallId,
                                            String toolSignature, Context context) {
        // 发送工具执行结果消息到 Wire
        wire.send(new ToolResultMessage(toolCallId, result));

        // 转换为上下文消息
        Message message = convertToolResultToMessage(result, toolCallId, toolSignature, context);
        return Mono.just(message);
    }

    /**
     * 处理工具执行错误
     */
    private Mono<Message> handleToolError(Throwable e, String toolName, String toolCallId) {
        log.error("Tool execution failed: {}", toolName, e);
        ToolResult errorResult = ToolResult.error("Tool execution error: " + e.getMessage(), "Execution failed");
        wire.send(new ToolResultMessage(toolCallId, errorResult));
        return Mono.just(Message.tool(toolCallId, "Tool execution error: " + e.getMessage()));
    }

    /**
     * 将工具结果转换为消息
     */
    private Message convertToolResultToMessage(ToolResult result, String toolCallId, String toolSignature, Context context) {

        String content;

        if (result.isOk()) {
            toolErrorTracker.clearErrors();
            content = offloadIfTooLarge(formatToolResult(result), result, toolCallId);

        } else if (result.isError()) {
            toolErrorTracker.trackError(toolSignature);
            content = toolErrorTracker.buildErrorContent(result.getMessage(), result.getOutput(), toolSignature);
        } else {
            content = result.getMessage();
        }

        return Message.tool(toolCallId, content);
    }

    /**
     * 输出过大时落盘，上下文仅保留摘要、预览与文件路径
     * <p>
     * 仅对成功结果生效：错误信息通常短且关键，截断反而丢掉排查依据。
     * 落盘失败时降级为原有行为（直接内联），不阻断工具链。
     *
     * @param content    已格式化的完整输出
     * @param result     工具结果（用于取 brief/message 作为摘要）
     * @param toolCallId 工具调用 ID，用作文件名
     * @return 内联内容或截断后的引用内容
     */
    private String offloadIfTooLarge(String content, ToolResult result, String toolCallId) {
        int maxInlineChars = toolOutputConfig.getMaxInlineChars();
        if (maxInlineChars <= 0 || content == null || content.length() <= maxInlineChars) {
            return content;
        }

        Path outputFile = workDir.resolve(".jimi").resolve("tool-output")
                .resolve(sessionId).resolve(toolCallId + ".txt");
        try {
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to offload large tool output to {}, keeping it inline", outputFile, e);
            return content;
        }

        String summary = result.getBrief() != null && !result.getBrief().isEmpty()
                ? result.getBrief()
                : result.getMessage();
        int previewChars = Math.min(Math.max(toolOutputConfig.getPreviewChars(), 0), content.length());
        long lineCount = content.lines().count();
        Path relativePath = workDir.relativize(outputFile);

        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isEmpty()) {
            sb.append(summary).append("\n\n");
        }
        if (previewChars > 0) {
            sb.append(content, 0, previewChars).append("\n\n");
        }
        sb.append(String.format(
                "[输出过大已截断。完整输出 %d 字符 / %d 行，已保存至 %s。使用 ReadFile 或 Grep 按需读取。]",
                content.length(), lineCount, relativePath));

        log.info("Offloaded large tool output ({} chars) to {}", content.length(), relativePath);
        return sb.toString();
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

}
