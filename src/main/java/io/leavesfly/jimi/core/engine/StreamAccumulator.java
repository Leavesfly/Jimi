package io.leavesfly.jimi.core.engine;

import io.leavesfly.jimi.llm.ChatCompletionChunk;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import io.leavesfly.jimi.llm.message.FunctionCall;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.ToolCall;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式响应累加器
 * <p>
 * 职责：累积 LLM 流式响应的内容和工具调用，构建完整的 Assistant 消息
 * <p>
 * 容错场景：
 * 1. temp_id 替换：某些 LLM 先发临时 ID，后发真实 ID
 * 2. 乱序发送：先发 arguments，后发 id 和 name
 * 3. 函数名延迟：函数名在后续 chunk 中才出现
 */
@Slf4j
@Getter
public class StreamAccumulator {

    private final StringBuilder contentBuilder = new StringBuilder();
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private ChatCompletionResult.Usage usage;

    // 当前正在构建的工具调用（容错：支持非标准顺序）
    private String currentToolCallId;
    private String currentFunctionName;
    private StringBuilder currentArguments = new StringBuilder();

    /**
     * 累积流式数据块
     *
     * @param chunk 数据块
     * @return this（支持链式调用）
     */
    public StreamAccumulator accumulate(ChatCompletionChunk chunk) {
        switch (chunk.getType()) {
            case CONTENT:
            case REASONING:
                accumulateContent(chunk);
                break;
            case TOOL_CALL:
                accumulateToolCall(chunk);
                break;
            case DONE:
                usage = chunk.getUsage();
                log.debug("Stream completed, usage: {}", usage);
                break;
        }
        return this;
    }

    /**
     * 累积内容
     */
    private void accumulateContent(ChatCompletionChunk chunk) {
        String contentDelta = chunk.getContentDelta();
        if (contentDelta != null && !contentDelta.isEmpty()) {
            contentBuilder.append(contentDelta);
        }
    }

    /**
     * 累积工具调用（保留容错逻辑）
     */
    private void accumulateToolCall(ChatCompletionChunk chunk) {
        if (chunk == null) {
            log.warn("收到 null 的 ToolCall chunk，忽略");
            return;
        }

        String newId = chunk.getToolCallId();
        if (newId != null && !newId.isEmpty()) {
            handleToolCallId(chunk, newId);
        }

        updateFunctionName(chunk);
        appendArgumentsDelta(chunk);
    }

    /**
     * 处理工具调用 ID（包含容错逻辑）
     */
    private void handleToolCallId(ChatCompletionChunk chunk, String newId) {
        // 情况1: temp_id 替换
        if (isTempIdReplacement(newId)) {
            log.debug("用实际 ID {} 替换临时 ID {}", newId, currentToolCallId);
            currentToolCallId = newId;
            if (chunk.getFunctionName() != null) {
                currentFunctionName = chunk.getFunctionName();
            }
            return;
        }

        // 情况2: 同一工具调用的后续 chunk
        if (newId.equals(currentToolCallId)) {
            if (chunk.getFunctionName() != null && currentFunctionName == null) {
                currentFunctionName = chunk.getFunctionName();
            }
            return;
        }

        // 情况3: 全新的工具调用
        finalizeCurrentToolCall();
        currentToolCallId = newId;
        currentFunctionName = chunk.getFunctionName();
        currentArguments = new StringBuilder();
    }

    private boolean isTempIdReplacement(String newId) {
        return currentToolCallId != null
                && currentToolCallId.startsWith("temp_")
                && !newId.startsWith("temp_");
    }

    /**
     * 更新函数名（处理函数名在后续 chunk 中才出现的情况）
     */
    private void updateFunctionName(ChatCompletionChunk chunk) {
        String functionName = chunk.getFunctionName();
        if (functionName == null || functionName.isEmpty()) {
            return;
        }
        if (currentToolCallId != null && currentFunctionName == null) {
            currentFunctionName = functionName;
            log.debug("更新 toolCallId={} 的函数名: {}", currentToolCallId, functionName);
        }
    }

    /**
     * 累积参数增量
     */
    private void appendArgumentsDelta(ChatCompletionChunk chunk) {
        String argumentsDelta = chunk.getArgumentsDelta();
        if (argumentsDelta == null || argumentsDelta.isEmpty()) {
            return;
        }

        // 如果没有当前工具调用上下文，创建临时上下文
        if (currentToolCallId == null) {
            String tempId = "temp_" + System.nanoTime() + "_" + Thread.currentThread().getId();
            log.warn("收到 argumentsDelta 但 currentToolCallId 为 null，创建临时上下文: id={}", tempId);
            currentToolCallId = tempId;
            currentFunctionName = null;
            currentArguments = new StringBuilder();
        }

        currentArguments.append(argumentsDelta);
    }

    /**
     * 完成当前未完成的工具调用
     */
    private void finalizeCurrentToolCall() {
        if (currentToolCallId != null && !currentToolCallId.isEmpty()) {
            toolCalls.add(buildToolCall());
            currentToolCallId = null;
            currentFunctionName = null;
            currentArguments = new StringBuilder();
        }
    }

    /**
     * 从当前状态构建 ToolCall
     */
    private ToolCall buildToolCall() {
        if (currentToolCallId == null) {
            log.error("构建 ToolCall 时缺少 toolCallId");
        }
        if (currentFunctionName == null) {
            log.error("构建 ToolCall 时缺少 functionName, toolCallId: {}", currentToolCallId);
        }

        return ToolCall.builder()
                .id(currentToolCallId)
                .type("function")
                .function(FunctionCall.builder()
                        .name(currentFunctionName)
                        .arguments(currentArguments.toString())
                        .build())
                .build();
    }

    /**
     * 构建完整的 Assistant 消息
     */
    public Message toAssistantMessage() {
        finalizeCurrentToolCall();

        String content = contentBuilder.toString();
        log.debug("构建 Assistant 消息: content_length={}, toolCalls_count={}", content.length(), toolCalls.size());

        return toolCalls.isEmpty()
                ? Message.assistant(content)
                : Message.assistant(content.isEmpty() ? null : content, toolCalls);
    }

    /**
     * 获取累积的内容
     */
    public String getContent() {
        return contentBuilder.toString();
    }

    /**
     * 是否有工具调用
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty() || currentToolCallId != null;
    }
}
