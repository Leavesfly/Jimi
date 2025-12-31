package io.leavesfly.jimi.core.engine.executor;


import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.core.engine.toolcall.ToolCallValidator;
import io.leavesfly.jimi.core.engine.toolcall.ToolErrorTracker;

import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.util.SpringContextUtils;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.message.ToolCallMessage;
import io.leavesfly.jimi.wire.message.ToolResultMessage;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 工具调度器
 * <p>
 * 职责：
 * - 工具调用验证
 * - 工具调用执行（串行）
 * - 工具错误跟踪
 * - 工具结果格式化
 */
@Slf4j
public class ToolDispatcher {

    private final ToolRegistry toolRegistry;
    private final Wire wire;
    private final ToolCallValidator toolCallValidator;
    private final ToolErrorTracker toolErrorTracker;


    /**
     * 基础构造函数
     */
    public ToolDispatcher(ToolRegistry toolRegistry) {

        this.toolRegistry = toolRegistry;
        wire = SpringContextUtils.getBean(Wire.class);
        toolCallValidator = SpringContextUtils.getBean(ToolCallValidator.class);
        toolErrorTracker = SpringContextUtils.getBean(ToolErrorTracker.class);
    }


    /**
     * 执行工具调用列表（串行执行）
     *
     * @param toolCalls 工具调用列表
     * @param context   上下文
     * @return 完成的 Mono
     */
    public Mono<Void> executeToolCalls(List<ToolCall> toolCalls, Context context) {
        log.info("Starting sequential execution of {} tool calls", toolCalls.size());

        return Flux.fromIterable(toolCalls).index().concatMap(tuple -> {
            long toolIndex = tuple.getT1();
            ToolCall toolCall = tuple.getT2();

            log.info("Executing tool call #{}/{}", toolIndex + 1, toolCalls.size());

            return executeToolCall(toolCall, context).doOnError(e -> log.error("Tool call #{} failed", toolIndex, e)).onErrorResume(e -> {
                log.error("Caught error in tool call #{}, returning error message", toolIndex, e);
                String toolCallId = (toolCall != null && toolCall.getId() != null) ? toolCall.getId() : "unknown_" + toolIndex;
                return Mono.just(Message.tool(toolCallId, "Tool execution failed: " + e.getMessage()));
            });
        }).collectList().doOnNext(results -> log.info("Collected {} tool results after sequential execution", results.size())).flatMap(results -> context.appendMessage(results).doOnSuccess(v -> log.info("Successfully appended {} tool results to context", results.size())).doOnError(e -> log.error("Failed to append tool results to context", e)));
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

                ToolCallValidator.ValidationResult validation = toolCallValidator.validate(toolCall);
                if (!validation.isValid()) {
                    log.error("Tool call validation failed: {}", validation.getErrorMessage());
                    ToolResult errorResult = ToolResult.error(validation.getErrorMessage(), "Validation failed");
                    wire.send(new ToolResultMessage(validation.getToolCallId(), errorResult));
                    return Mono.just(Message.tool(validation.getToolCallId(), validation.getErrorMessage()));
                }

                String toolName = toolCall.getFunction().getName();
                String toolCallId = validation.getToolCallId();
                String rawArgs = toolCall.getFunction().getArguments();
                String toolSignature = toolName + ":" + rawArgs;

                return executeValidToolCall(toolName, rawArgs, toolCallId, toolSignature, context);
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

        return toolRegistry.execute(toolName, arguments).doOnNext(result -> {
            // 发送工具执行结果消息到 Wire
            wire.send(new ToolResultMessage(toolCallId, result));
        }).map(result -> convertToolResultToMessage(result, toolCallId, toolSignature, context)).onErrorResume(e -> {
            log.error("Tool execution failed: {}", toolName, e);
            ToolResult errorResult = ToolResult.error("Tool execution error: " + e.getMessage(), "Execution failed");
            wire.send(new ToolResultMessage(toolCallId, errorResult));


            return Mono.just(Message.tool(toolCallId, "Tool execution error: " + e.getMessage()));
        });
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
