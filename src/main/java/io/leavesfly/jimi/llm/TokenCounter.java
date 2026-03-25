package io.leavesfly.jimi.llm;

import io.leavesfly.jimi.llm.message.FunctionCall;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.ToolCall;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Token 计数器
 * <p>
 * 提供比简单 charCount/4 更精确的 Token 估算。
 * 基于 OpenAI tiktoken 的 cl100k_base 编码规则的近似实现：
 * <p>
 * 核心规则：
 * - 英文单词：约 1 token / word（短词 1 token，长词可能 2-3 tokens）
 * - 中文/日文/韩文字符：约 1-2 tokens / 字符
 * - 数字序列：每 1-3 位约 1 token
 * - 空白和标点：通常与相邻文本合并为 1 token
 * - 代码标识符：驼峰/下划线分割后每段约 1 token
 * <p>
 * 每条消息还有固定开销（role、格式标记等），约 4 tokens。
 */
@Slf4j
public class TokenCounter {

    /**
     * 每条消息的固定 token 开销（role 标记、分隔符等）
     */
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    /**
     * 工具调用的固定开销（function name、id、type 标记等）
     */
    private static final int TOOL_CALL_OVERHEAD_TOKENS = 8;

    // 匹配模式
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff\\uac00-\\ud7af]");
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z]+");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * 估算单条消息的 token 数
     *
     * @param message 消息
     * @return 估算的 token 数
     */
    public static int estimateTokens(Message message) {
        int tokens = MESSAGE_OVERHEAD_TOKENS;

        // 估算文本内容
        String textContent = message.getTextContent();
        if (textContent != null && !textContent.isEmpty()) {
            tokens += estimateTextTokens(textContent);
        }

        // 估算工具调用
        if (message.getToolCalls() != null) {
            for (ToolCall toolCall : message.getToolCalls()) {
                tokens += estimateToolCallTokens(toolCall);
            }
        }

        // 估算 tool_call_id（tool 角色消息）
        if (message.getToolCallId() != null) {
            tokens += 2; // tool_call_id 标记
        }

        return tokens;
    }

    /**
     * 估算文本的 token 数
     * <p>
     * 使用混合策略：
     * - 先统计 CJK 字符数（每个约 1.5 tokens）
     * - 再统计英文单词数（每个约 1.3 tokens，考虑子词分割）
     * - 再统计数字序列（每 3 位约 1 token）
     * - 标点和空白通常与相邻内容合并
     *
     * @param text 文本内容
     * @return 估算的 token 数
     */
    public static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        double tokenEstimate = 0;

        // 统计 CJK 字符（中日韩）
        int cjkCount = 0;
        Matcher cjkMatcher = CJK_PATTERN.matcher(text);
        while (cjkMatcher.find()) {
            cjkCount++;
        }
        // CJK 字符平均约 1.5 tokens/字符（tiktoken 中大部分中文字是 2 tokens，少部分是 1）
        tokenEstimate += cjkCount * 1.5;

        // 移除 CJK 字符后处理剩余文本
        String nonCjkText = CJK_PATTERN.matcher(text).replaceAll(" ");

        // 统计英文单词
        Matcher wordMatcher = WORD_PATTERN.matcher(nonCjkText);
        while (wordMatcher.find()) {
            String word = wordMatcher.group();
            // 短词（<=4字符）通常是 1 token
            // 中等词（5-10字符）约 1-2 tokens
            // 长词（>10字符）约 2-3 tokens
            if (word.length() <= 4) {
                tokenEstimate += 1;
            } else if (word.length() <= 10) {
                tokenEstimate += 1.5;
            } else {
                tokenEstimate += Math.ceil(word.length() / 5.0);
            }
        }

        // 统计数字序列
        Matcher numberMatcher = NUMBER_PATTERN.matcher(nonCjkText);
        while (numberMatcher.find()) {
            String number = numberMatcher.group();
            // 每 1-3 位数字约 1 token
            tokenEstimate += Math.ceil(number.length() / 3.0);
        }

        // 标点和特殊字符（非字母、非数字、非空白、非CJK）
        String remaining = nonCjkText.replaceAll("[a-zA-Z0-9\\s]", "");
        // 标点通常与相邻文本合并，但独立标点约 1 token
        tokenEstimate += remaining.length() * 0.5;

        // 空白字符通常与相邻 token 合并，不单独计数
        // 但换行符可能产生额外 token
        long newlineCount = text.chars().filter(c -> c == '\n').count();
        tokenEstimate += newlineCount * 0.5;

        return Math.max(1, (int) Math.ceil(tokenEstimate));
    }

    /**
     * 估算工具调用的 token 数
     */
    private static int estimateToolCallTokens(ToolCall toolCall) {
        int tokens = TOOL_CALL_OVERHEAD_TOKENS;

        if (toolCall.getId() != null) {
            tokens += 1; // tool call id 通常是 1 token
        }

        if (toolCall.getFunction() != null) {
            FunctionCall function = toolCall.getFunction();

            // 函数名
            if (function.getName() != null) {
                tokens += estimateTextTokens(function.getName());
            }

            // 函数参数（JSON 格式）
            if (function.getArguments() != null) {
                tokens += estimateJsonTokens(function.getArguments());
            }
        }

        return tokens;
    }

    /**
     * 估算 JSON 字符串的 token 数
     * JSON 的 token 化效率比普通文本低（大量的引号、冒号、逗号等结构字符）
     */
    private static int estimateJsonTokens(String json) {
        if (json == null || json.isEmpty()) {
            return 0;
        }

        // JSON 的 token 密度比普通文本低
        // 结构字符（{, }, [, ], :, ,, "）通常与相邻内容合并
        // 但整体上 JSON 的 token/char 比率约为 1:3（比普通英文的 1:4 高）
        int textTokens = estimateTextTokens(json);

        // JSON 结构开销：大约增加 20%
        return (int) Math.ceil(textTokens * 1.2);
    }

    /**
     * 估算消息列表的总 token 数
     *
     * @param messages 消息列表
     * @return 估算的总 token 数
     */
    public static int estimateTokens(List<Message> messages) {
        int total = 3; // 每次对话的固定开销（priming tokens）
        for (Message message : messages) {
            total += estimateTokens(message);
        }
        return total;
    }
}
