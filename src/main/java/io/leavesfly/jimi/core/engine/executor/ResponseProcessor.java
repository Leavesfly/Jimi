package io.leavesfly.jimi.core.engine.executor;

import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.core.engine.toolcall.ToolCallFilter;
import io.leavesfly.jimi.llm.ChatCompletionChunk;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import io.leavesfly.jimi.llm.message.FunctionCall;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.message.ContentPartMessage;
import io.leavesfly.jimi.wire.message.TokenUsageMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 响应处理器
 * <p>
 * 职责：
 * - 处理 LLM 流式响应数据块
 * - 累积内容和工具调用
 * - 构建完整的 Assistant 消息
 * - 处理 Token 统计
 */
@Slf4j
@Component
public class ResponseProcessor {

    @Autowired
    private Wire wire;

    @Autowired
    private ToolCallFilter toolCallFilter;


    /**
     * 设置 Wire（用于 Spring Bean 注入后设置依赖）
     */
    public void setWire(Wire wire) {
        this.wire = wire;
    }

    /**
     * 处理流式数据块
     *
     * @param acc   流式累加器
     * @param chunk 数据块
     * @return 更新后的累加器
     */
    public StreamAccumulator processStreamChunk(StreamAccumulator acc, ChatCompletionChunk chunk) {
        switch (chunk.getType()) {
            case CONTENT:
                handleContentChunk(acc, chunk);
                break;
            case TOOL_CALL:
                handleToolCallChunk(acc, chunk);
                break;
            case DONE:
                handleDoneChunk(acc, chunk);
                break;
        }
        return acc;
    }

    /**
     * 处理内容数据块
     */
    private void handleContentChunk(StreamAccumulator acc, ChatCompletionChunk chunk) {
        String contentDelta = chunk.getContentDelta();
        if (contentDelta != null && !contentDelta.isEmpty()) {
            acc.contentBuilder.append(contentDelta);
            // 根据是否为推理内容发送不同类型的消息
            ContentPartMessage.ContentType contentType = chunk.isReasoning()
                    ? ContentPartMessage.ContentType.REASONING
                    : ContentPartMessage.ContentType.NORMAL;
            wire.send(new ContentPartMessage(new TextPart(contentDelta), contentType));
        }
    }

    /**
     * 处理工具调用数据块
     * <p>
     * OpenAI 流式 API 规范：
     * - 第一个 chunk 包含 id 和 function.name
     * - 后续 chunk 只包含 function.arguments 的增量（不包含 id）
     * - 新的工具调用开始时才会有新的 id
     * <p>
     * 容错处理：
     * - 某些 LLM 可能先发送 arguments，后发送 id 和 name
     * - 使用临时 ID 机制确保数据不丢失
     */
    private void handleToolCallChunk(StreamAccumulator acc, ChatCompletionChunk chunk) {
        // 防御性检查
        if (chunk == null) {
            log.warn("收到 null 的 ToolCall chunk，忽略");
            return;
        }

        if (isNewToolCallStart(chunk)) {
            startNewToolCall(acc, chunk);
        }

        updateFunctionName(acc, chunk);
        appendArgumentsDelta(acc, chunk);
    }

    /**
     * 判断是否为新工具调用的开始
     */
    private boolean isNewToolCallStart(ChatCompletionChunk chunk) {
        return chunk.getToolCallId() != null && !chunk.getToolCallId().isEmpty();
    }

    /**
     * 开始新的工具调用
     */
    private void startNewToolCall(StreamAccumulator acc, ChatCompletionChunk chunk) {
        String newToolCallId = chunk.getToolCallId();

        // 检查是否是对临时 ID 的替换
        boolean isReplacingTempId = acc.currentToolCallId != null
                && acc.currentToolCallId.startsWith("temp_")
                && !newToolCallId.startsWith("temp_");

        if (isReplacingTempId) {
            log.debug("用实际 ID {} 替换临时 ID {}", newToolCallId, acc.currentToolCallId);
            acc.currentToolCallId = newToolCallId;
            if (chunk.getFunctionName() != null) {
                acc.currentFunctionName = chunk.getFunctionName();
            }
            return;
        }

        // 检查是否是同一个工具调用的后续 chunk
        if (newToolCallId.equals(acc.currentToolCallId)) {
            if (chunk.getFunctionName() != null && acc.currentFunctionName == null) {
                acc.currentFunctionName = chunk.getFunctionName();
            }
            return;
        }

        // 这是一个全新的工具调用
        if (acc.currentToolCallId != null) {
            // 保存前一个工具调用
            acc.toolCalls.add(buildToolCall(acc));
        }

        // 初始化新的工具调用
        acc.currentToolCallId = newToolCallId;
        acc.currentFunctionName = chunk.getFunctionName();
        acc.currentArguments = new StringBuilder();
    }

    /**
     * 更新函数名（处理函数名在后续 chunk 中才出现的情况）
     */
    private void updateFunctionName(StreamAccumulator acc, ChatCompletionChunk chunk) {
        String functionName = chunk.getFunctionName();
        if (functionName == null || functionName.isEmpty()) {
            return;
        }

        if (acc.currentToolCallId != null && acc.currentFunctionName == null) {
            acc.currentFunctionName = functionName;
            log.debug("更新 toolCallId={} 的函数名: {}", acc.currentToolCallId, functionName);
        }
    }

    /**
     * 累积参数增量
     */
    private void appendArgumentsDelta(StreamAccumulator acc, ChatCompletionChunk chunk) {
        String argumentsDelta = chunk.getArgumentsDelta();
        if (argumentsDelta == null || argumentsDelta.isEmpty()) {
            return;
        }

        // 如果没有当前工具调用上下文，创建临时上下文
        if (acc.currentToolCallId == null) {
            initializeTempToolCallContext(acc, argumentsDelta);
        }

        acc.currentArguments.append(argumentsDelta);
    }

    /**
     * 初始化临时工具调用上下文
     */
    private void initializeTempToolCallContext(StreamAccumulator acc, String firstArgumentsDelta) {
        String tempId = "temp_" + System.nanoTime() + "_" + Thread.currentThread().getId();

        log.warn("收到 argumentsDelta 但 currentToolCallId 为 null，创建临时上下文: id={}", tempId);

        acc.currentToolCallId = tempId;
        acc.currentFunctionName = null;
        acc.currentArguments = new StringBuilder();
    }

    /**
     * 处理完成数据块
     */
    private void handleDoneChunk(StreamAccumulator acc, ChatCompletionChunk chunk) {
        log.debug("Stream completed, usage: {}", chunk.getUsage());
        acc.usage = chunk.getUsage();
    }

    /**
     * 处理流式完成后的逻辑
     *
     * @param acc     累加器
     * @param context 上下文
     * @return 是否完成（true 表示没有更多工具调用）
     */
    public Mono<Message> handleStreamCompletion(StreamAccumulator acc, Context context) {
        Message assistantMessage = buildMessageFromAccumulator(acc);

        // Token 计数更新
        Mono<Void> updateTokens;
        if (acc.usage != null) {
            int newTotalTokens = context.getTokenCount() + acc.usage.getTotalTokens();
            updateTokens = context.updateTokenCount(newTotalTokens);
            log.debug("Updated token count: {} + {} = {}",
                    context.getTokenCount(), acc.usage.getTotalTokens(), newTotalTokens);

            // 发送 Token 使用统计消息到 Wire
            wire.send(new TokenUsageMessage(acc.usage));
        } else {
            // LLM 未返回 usage 信息，使用字符数估算
            int estimatedTokens = estimateTokensFromMessage(assistantMessage);
            int newTotalTokens = context.getTokenCount() + estimatedTokens;
            updateTokens = context.updateTokenCount(newTotalTokens);
            log.debug("Estimated token count (no usage from LLM): {} + {} = {}",
                    context.getTokenCount(), estimatedTokens, newTotalTokens);
        }

        return updateTokens
                .then(context.appendMessage(assistantMessage))
                .thenReturn(assistantMessage);
    }

    /**
     * 处理 LLM 调用错误（静默处理）
     *
     * @param e 异常
     * @return 表示完成的 Mono
     */
    public Mono<Boolean> handleLLMError(Throwable e) {
        // 只在 DEBUG 级别记录错误
        if (e instanceof WebClientResponseException) {
            WebClientResponseException webEx = (WebClientResponseException) e;
            log.debug("LLM API call failed: status={}, body={}",
                    webEx.getStatusCode(), webEx.getResponseBodyAsString());
        } else {
            log.debug("LLM API call failed: {}", e.getMessage());
        }
        // 静默结束，不输出错误信息到用户界面
        return Mono.just(true);
    }

    /**
     * 从累加器构建 ToolCall
     */
    private ToolCall buildToolCall(StreamAccumulator acc) {
        if (acc.currentToolCallId == null) {
            log.error("构建 ToolCall 时缺少 toolCallId");
        }
        if (acc.currentFunctionName == null) {
            log.error("构建 ToolCall 时缺少 functionName, toolCallId: {}", acc.currentToolCallId);
        }

        String arguments = acc.currentArguments.toString();

        return ToolCall.builder()
                .id(acc.currentToolCallId)
                .type("function")
                .function(FunctionCall.builder()
                        .name(acc.currentFunctionName)
                        .arguments(arguments)
                        .build())
                .build();
    }

    /**
     * 从累加器构建完整的 Message
     */
    public Message buildMessageFromAccumulator(StreamAccumulator acc) {
        finalizeCurrentToolCall(acc);

        String content = acc.contentBuilder.toString();
        int contentLength = content.length();
        int toolCallsCount = acc.toolCalls.size();

        log.debug("构建 Assistant 消息: content_length={}, toolCalls_count={}", contentLength, toolCallsCount);

        List<ToolCall> validToolCalls = toolCallFilter.filterValid(acc.toolCalls);
        log.info("过滤后有效工具调用数量: {} (原始: {})", validToolCalls.size(), acc.toolCalls.size());

        return validToolCalls.isEmpty()
                ? Message.assistant(content)
                : Message.assistant(content.isEmpty() ? null : content, validToolCalls);
    }

    /**
     * 完成当前未完成的工具调用
     */
    private void finalizeCurrentToolCall(StreamAccumulator acc) {
        if (acc.currentToolCallId != null && !acc.currentToolCallId.isEmpty()) {
            acc.toolCalls.add(buildToolCall(acc));
            acc.currentToolCallId = null;
            acc.currentFunctionName = null;
            acc.currentArguments = new StringBuilder();
        }
    }

    /**
     * 从消息估算 Token 数量（回退机制）
     */
    public int estimateTokensFromMessage(Message message) {
        int charCount = 0;

        // 估算内容部分的字符数
        String textContent = message.getTextContent();
        if (textContent != null && !textContent.isEmpty()) {
            charCount += textContent.length();
        }

        // 估算工具调用的字符数
        if (message.getToolCalls() != null) {
            for (ToolCall toolCall : message.getToolCalls()) {
                if (toolCall.getId() != null) {
                    charCount += toolCall.getId().length();
                }
                if (toolCall.getFunction() != null) {
                    FunctionCall function = toolCall.getFunction();
                    if (function.getName() != null) {
                        charCount += function.getName().length();
                    }
                    if (function.getArguments() != null) {
                        charCount += function.getArguments().length();
                    }
                }
            }
        }

        // 使用字符数除以 4 作为 Token 数估算
        int estimatedTokens = (int) Math.ceil(charCount / 4.0);
        log.debug("Estimated {} tokens from {} characters", estimatedTokens, charCount);
        return estimatedTokens;
    }

    /**
     * 流式累加器
     * <p>
     * 用于在流式处理过程中累积内容和工具调用
     */
    @Getter
    public static class StreamAccumulator {
        StringBuilder contentBuilder = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        ChatCompletionResult.Usage usage;

        // 用于临时存储正在构建的工具调用
        String currentToolCallId;
        String currentFunctionName;
        StringBuilder currentArguments = new StringBuilder();

        /**
         * 获取累积的内容
         */
        public String getContent() {
            return contentBuilder.toString();
        }

        /**
         * 获取是否有工具调用
         */
        public boolean hasToolCalls() {
            return !toolCalls.isEmpty() || currentToolCallId != null;
        }
    }
}
