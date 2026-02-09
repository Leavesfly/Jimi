package io.leavesfly.jimi.adk.core.tool;

import io.leavesfly.jimi.adk.api.tool.ToolResult;

/**
 * 工具结果构建器
 * <p>
 * 用于构建带输出限制的工具结果，防止工具输出过大导致上下文溢出。
 * <p>
 * 功能：
 * - 限制总输出字符数
 * - 限制单行最大长度
 * - 自动截断并添加截断标记
 */
public class ToolResultBuilder {

    private static final int DEFAULT_MAX_CHARS = 50_000;
    private static final int DEFAULT_MAX_LINE_LENGTH = 2000;
    private static final String TRUNCATION_MARKER = "[...truncated]";

    private final int maxChars;
    private final Integer maxLineLength;
    private final StringBuilder buffer;
    private int nChars;
    private int nLines;
    private boolean truncationHappened;

    public ToolResultBuilder() {
        this(DEFAULT_MAX_CHARS, DEFAULT_MAX_LINE_LENGTH);
    }

    public ToolResultBuilder(int maxChars, Integer maxLineLength) {
        this.maxChars = maxChars;
        this.maxLineLength = maxLineLength;
        this.buffer = new StringBuilder();
        this.nChars = 0;
        this.nLines = 0;
        this.truncationHappened = false;
    }

    /**
     * 写入文本到输出缓冲区
     *
     * @param text 要写入的文本
     * @return 实际写入的字符数
     */
    public int write(String text) {
        if (isFull()) {
            return 0;
        }

        if (text == null || text.isEmpty()) {
            return 0;
        }

        String[] lines = text.split("\n", -1);
        int charsWritten = 0;

        for (int i = 0; i < lines.length; i++) {
            if (isFull()) {
                break;
            }

            String line = lines[i];
            if (i < lines.length - 1) {
                line += "\n";
            }

            String originalLine = line;
            int remainingChars = maxChars - nChars;
            int limit = maxLineLength != null
                    ? Math.min(remainingChars, maxLineLength)
                    : remainingChars;

            line = truncateLine(line, limit);
            if (!line.equals(originalLine)) {
                truncationHappened = true;
            }

            buffer.append(line);
            charsWritten += line.length();
            nChars += line.length();
            if (line.endsWith("\n")) {
                nLines++;
            }
        }

        return charsWritten;
    }

    /**
     * 写入一行文本（自动添加换行）
     *
     * @param line 行内容
     * @return 实际写入的字符数
     */
    public int writeLine(String line) {
        return write(line + "\n");
    }

    /**
     * 创建成功结果
     */
    public ToolResult ok(String message) {
        String output = buffer.toString();
        String finalMessage = appendTruncationNote(message);
        return ToolResult.success(output.isEmpty() ? finalMessage : output);
    }

    /**
     * 创建成功结果（带数据）
     */
    public ToolResult ok(String message, Object data) {
        String output = buffer.toString();
        String finalMessage = appendTruncationNote(message);
        return ToolResult.success(output.isEmpty() ? finalMessage : output, data);
    }

    /**
     * 创建错误结果
     */
    public ToolResult error(String message) {
        String finalMessage = appendTruncationNote(message);
        return ToolResult.error(finalMessage);
    }

    /**
     * 截断行
     */
    private String truncateLine(String line, int maxLength) {
        if (line.length() <= maxLength) {
            return line;
        }

        // 保留行尾的换行符
        String linebreak = "";
        if (line.endsWith("\r\n")) {
            linebreak = "\r\n";
        } else if (line.endsWith("\n")) {
            linebreak = "\n";
        } else if (line.endsWith("\r")) {
            linebreak = "\r";
        }

        String end = TRUNCATION_MARKER + linebreak;
        maxLength = Math.max(maxLength, end.length());
        return line.substring(0, maxLength - end.length()) + end;
    }

    /**
     * 添加截断提示
     */
    private String appendTruncationNote(String message) {
        if (!truncationHappened) {
            return message;
        }
        String truncationMsg = " (输出被截断以适应消息限制)";
        return message == null ? truncationMsg.trim() : message + truncationMsg;
    }

    /**
     * 检查缓冲区是否已满
     */
    public boolean isFull() {
        return nChars >= maxChars;
    }

    /**
     * 获取当前字符数
     */
    public int getNChars() {
        return nChars;
    }

    /**
     * 获取当前行数
     */
    public int getNLines() {
        return nLines;
    }

    /**
     * 获取当前缓冲内容
     */
    public String getContent() {
        return buffer.toString();
    }
}
