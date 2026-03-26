package io.leavesfly.jimi.llm.provider;

import io.leavesfly.jimi.llm.ChatCompletionChunk;

/**
 * Think 标签解析器
 * 处理流式响应中的 <think> 和 </think> 标签
 * 用于识别和标记 AI 的推理/思考内容
 */
public class ThinkTagParser {

    // <think>标签解析状态（流式处理）- 保留 volatile 修复
    private volatile boolean insideThinkTag = false;
    private volatile StringBuilder thinkTagBuffer = new StringBuilder();

    /**
     * 重置解析器状态
     * 每次新请求前调用
     */
    public void reset() {
        insideThinkTag = false;
        thinkTagBuffer = new StringBuilder();
    }

    /**
     * 解析内容中的 <think> 标签
     * 支持流式处理,正确识别标签边界
     *
     * @param contentDelta 内容增量
     * @return 处理后的chunk
     */
    public ChatCompletionChunk parse(String contentDelta) {
        // 将缓冲区和Delta合并后再处理
        thinkTagBuffer.append(contentDelta);
        String fullContent = thinkTagBuffer.toString();

        StringBuilder processedContent = new StringBuilder();
        // 记录第一个实际字符的reasoning状态（而不是最后一个标签的状态）
        Boolean contentIsReasoning = null;

        int i = 0;
        int lastProcessedIndex = 0; // 记录已处理的位置

        while (i < fullContent.length()) {
            // 检查是否遇到<think>标签开始
            if (!insideThinkTag && fullContent.startsWith("<think>", i)) {
                insideThinkTag = true;
                i += 7; // 跳过"<think>"
                lastProcessedIndex = i;
                continue;
            }

            // 检查是否遇到</think>标签结束
            if (insideThinkTag && fullContent.startsWith("</think>", i)) {
                insideThinkTag = false;
                i += 8; // 跳过"</think>"
                lastProcessedIndex = i;
                continue;
            }

            // 检查是否可能是部分标签(需要等待下一个chunk)
            if (i >= fullContent.length() - 8) {
                // 剩余的字符不够组成完整标签,检查是否是标签的开头
                String remaining = fullContent.substring(i);
                if ("<think>".startsWith(remaining) || "</think>".startsWith(remaining)) {
                    break;
                }
            }

            // 添加普通字符
            processedContent.append(fullContent.charAt(i));
            // 记录第一个实际字符时的reasoning状态
            if (contentIsReasoning == null) {
                contentIsReasoning = insideThinkTag;
            }
            lastProcessedIndex = i + 1;
            i++;
        }

        // 更新缓冲区：保留未处理的部分
        if (lastProcessedIndex < fullContent.length()) {
            // 有未处理的部分（如部分标签），保留在缓冲区
            thinkTagBuffer = new StringBuilder(fullContent.substring(lastProcessedIndex));
        } else {
            // 所有内容都处理完毕，清空缓冲区
            thinkTagBuffer = new StringBuilder();
        }

        // 如果没有实际内容(只有标签),返回空chunk
        if (processedContent.length() == 0) {
            return ChatCompletionChunk.builder()
                    .type(ChatCompletionChunk.ChunkType.CONTENT)
                    .contentDelta("")
                    .build();
        }

        // 返回处理后的内容,带上正确的reasoning标记（使用第一个字符时的状态）
        boolean isReasoning = contentIsReasoning != null && contentIsReasoning;

        return ChatCompletionChunk.builder()
                .type(ChatCompletionChunk.ChunkType.CONTENT)
                .contentDelta(processedContent.toString())
                .isReasoning(isReasoning)
                .build();
    }
}
