package io.leavesfly.jimi.adk.core.engine;

import io.leavesfly.jimi.adk.api.llm.ChatCompletionChunk;
import io.leavesfly.jimi.adk.api.message.TextPart;
import io.leavesfly.jimi.adk.api.message.ToolCall;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.core.wire.messages.ContentPartMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式响应处理器
 * <p>
 * 职责：
 * - 处理 LLM 流式响应块（ChatCompletionChunk）
 * - 累积文本内容、推理内容、工具调用
 * - 通过 Wire 消息总线实时推送内容
 * </p>
 */
@Slf4j
public class StreamResponseHandler {

    private final Wire wire;

    public StreamResponseHandler(Wire wire) {
        this.wire = wire;
    }

    /**
     * 处理单个流式响应块，累积到 StreamAccumulator
     *
     * @param acc   累积器
     * @param chunk 流式响应块
     * @return 更新后的累积器
     */
    public StreamAccumulator processChunk(StreamAccumulator acc, ChatCompletionChunk chunk) {
        if (chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
            // 记录 Token 使用
            if (chunk.getUsage() != null) {
                acc.setUsage(chunk.getUsage());
            }
            return acc;
        }

        ChatCompletionChunk.Choice choice = chunk.getChoices().get(0);
        ChatCompletionChunk.Delta delta = choice.getDelta();

        if (delta == null) {
            return acc;
        }

        // 处理文本内容
        if (delta.getContent() != null) {
            acc.getContentBuilder().append(delta.getContent());
            wire.send(new ContentPartMessage(TextPart.of(delta.getContent())));
        }

        // 处理推理内容
        if (delta.getReasoningContent() != null) {
            acc.getReasoningBuilder().append(delta.getReasoningContent());
            wire.send(new ContentPartMessage(
                    TextPart.of(delta.getReasoningContent()),
                    ContentPartMessage.ContentType.REASONING));
        }

        // 处理工具调用
        if (delta.getToolCalls() != null) {
            processToolCalls(acc, delta.getToolCalls());
        }

        return acc;
    }

    /**
     * 处理工具调用增量
     */
    private void processToolCalls(StreamAccumulator acc, List<ToolCall> deltaToolCalls) {
        for (ToolCall tc : deltaToolCalls) {
            if (tc.getId() != null || (tc.getFunction() != null && tc.getFunction().getName() != null)) {
                // 新的工具调用
                acc.getToolCalls().add(tc);
            } else if (tc.getFunction() != null && tc.getFunction().getArguments() != null) {
                // 追加参数到最后一个工具调用
                if (!acc.getToolCalls().isEmpty()) {
                    ToolCall lastTc = acc.getToolCalls().get(acc.getToolCalls().size() - 1);
                    if (lastTc.getFunction() != null) {
                        String currentArgs = lastTc.getFunction().getArguments();
                        lastTc.getFunction().setArguments(
                                (currentArgs == null ? "" : currentArgs) + tc.getFunction().getArguments()
                        );
                    }
                }
            }
        }
    }

    /**
     * 流式响应累积器
     * <p>
     * 累积 LLM 流式响应的文本内容、推理内容、工具调用和 Token 用量
     * </p>
     */
    public static class StreamAccumulator {
        private final StringBuilder contentBuilder = new StringBuilder();
        private final StringBuilder reasoningBuilder = new StringBuilder();
        private final List<ToolCall> toolCalls = new ArrayList<>();
        private ChatCompletionChunk.Usage usage;

        public StringBuilder getContentBuilder() {
            return contentBuilder;
        }

        public StringBuilder getReasoningBuilder() {
            return reasoningBuilder;
        }

        public List<ToolCall> getToolCalls() {
            return toolCalls;
        }

        public ChatCompletionChunk.Usage getUsage() {
            return usage;
        }

        public void setUsage(ChatCompletionChunk.Usage usage) {
            this.usage = usage;
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }

        public String getContent() {
            return contentBuilder.toString();
        }
    }
}
