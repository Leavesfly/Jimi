package io.leavesfly.jimi.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Debug 日志输出器
 * <p>
 * 当 --debug 模式启用时，输出 LLM 请求/响应的详细信息到标准错误流（stderr），
 * 避免与正常的 AI 输出混淆。
 * <p>
 * 输出内容包括：
 * - LLM 请求：模型名、消息数量、工具数量、请求体摘要
 * - LLM 响应：耗时、Token 使用量、响应类型
 * - 工具调用：工具名、参数摘要、执行耗时
 */
public class DebugLogger {

    private static final AtomicBoolean DEBUG_ENABLED = new AtomicBoolean(false);
    private static final AtomicReference<Instant> LAST_REQUEST_TIME = new AtomicReference<>();

    private static final String RESET = "\u001B[0m";
    private static final String YELLOW = "\u001B[33m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";
    private static final String GRAY = "\u001B[90m";
    private static final String BOLD = "\u001B[1m";

    /**
     * 启用 debug 模式
     */
    public static void enable() {
        DEBUG_ENABLED.set(true);
        debugPrint(BOLD + MAGENTA + "🔍 Debug mode enabled" + RESET);
    }

    /**
     * 检查 debug 模式是否启用
     */
    public static boolean isEnabled() {
        return DEBUG_ENABLED.get();
    }

    /**
     * 记录 LLM 请求信息
     *
     * @param providerName 提供商名称
     * @param modelName    模型名称
     * @param messageCount 消息数量
     * @param toolCount    工具数量
     * @param isStream     是否流式请求
     */
    public static void logLLMRequest(String providerName, String modelName,
                                     int messageCount, int toolCount, boolean isStream) {
        if (!DEBUG_ENABLED.get()) return;

        LAST_REQUEST_TIME.set(Instant.now());

        StringBuilder sb = new StringBuilder();
        sb.append(GRAY).append("─".repeat(60)).append(RESET).append("\n");
        sb.append(BOLD).append(CYAN).append("📤 LLM Request").append(RESET).append("\n");
        sb.append(GRAY).append("  Provider: ").append(RESET).append(providerName).append("\n");
        sb.append(GRAY).append("  Model:    ").append(RESET).append(modelName).append("\n");
        sb.append(GRAY).append("  Messages: ").append(RESET).append(messageCount).append("\n");
        sb.append(GRAY).append("  Tools:    ").append(RESET).append(toolCount).append("\n");
        sb.append(GRAY).append("  Stream:   ").append(RESET).append(isStream).append("\n");

        debugPrint(sb.toString());
    }

    /**
     * 记录 LLM 请求体详情（仅在 debug 模式下）
     *
     * @param requestBody 请求体 JSON 字符串
     */
    public static void logLLMRequestBody(String requestBody) {
        if (!DEBUG_ENABLED.get()) return;

        String truncated = truncateForDisplay(requestBody, 2000);
        StringBuilder sb = new StringBuilder();
        sb.append(GRAY).append("  Request Body:").append(RESET).append("\n");
        sb.append(GRAY).append(indent(truncated, 4)).append(RESET).append("\n");

        debugPrint(sb.toString());
    }

    /**
     * 记录 LLM 响应完成
     *
     * @param contentLength 内容长度
     * @param toolCallCount 工具调用数量
     * @param promptTokens  输入 Token 数
     * @param completionTokens 输出 Token 数
     * @param totalTokens   总 Token 数
     */
    public static void logLLMResponse(int contentLength, int toolCallCount,
                                      int promptTokens, int completionTokens, int totalTokens) {
        if (!DEBUG_ENABLED.get()) return;

        Instant requestTime = LAST_REQUEST_TIME.get();
        String elapsed = requestTime != null
                ? Duration.between(requestTime, Instant.now()).toMillis() + "ms"
                : "unknown";

        StringBuilder sb = new StringBuilder();
        sb.append(BOLD).append(YELLOW).append("📥 LLM Response").append(RESET)
                .append(GRAY).append(" (").append(elapsed).append(")").append(RESET).append("\n");
        sb.append(GRAY).append("  Content:    ").append(RESET).append(contentLength).append(" chars\n");
        sb.append(GRAY).append("  ToolCalls:  ").append(RESET).append(toolCallCount).append("\n");

        if (totalTokens > 0) {
            sb.append(GRAY).append("  Tokens:     ").append(RESET)
                    .append("prompt=").append(promptTokens)
                    .append(", completion=").append(completionTokens)
                    .append(", total=").append(totalTokens).append("\n");
        }

        debugPrint(sb.toString());
    }

    /**
     * 记录工具执行信息
     *
     * @param toolName  工具名称
     * @param arguments 参数摘要
     */
    public static void logToolExecution(String toolName, String arguments) {
        if (!DEBUG_ENABLED.get()) return;

        String truncatedArgs = truncateForDisplay(arguments, 200);
        StringBuilder sb = new StringBuilder();
        sb.append(GRAY).append("  🔧 Tool: ").append(RESET).append(toolName)
                .append(GRAY).append(" args=").append(truncatedArgs).append(RESET).append("\n");

        debugPrint(sb.toString());
    }

    /**
     * 记录工具执行结果
     *
     * @param toolName   工具名称
     * @param elapsedMs  耗时（毫秒）
     * @param resultSize 结果大小
     */
    public static void logToolResult(String toolName, long elapsedMs, int resultSize) {
        if (!DEBUG_ENABLED.get()) return;

        StringBuilder sb = new StringBuilder();
        sb.append(GRAY).append("  ✅ ").append(toolName)
                .append(" completed in ").append(elapsedMs).append("ms")
                .append(", result=").append(resultSize).append(" chars")
                .append(RESET).append("\n");

        debugPrint(sb.toString());
    }

    /**
     * 记录通用 debug 信息
     */
    public static void logDebug(String message) {
        if (!DEBUG_ENABLED.get()) return;
        debugPrint(GRAY + "  [DEBUG] " + message + RESET);
    }

    /**
     * 输出到 stderr，避免与正常输出混淆
     */
    private static void debugPrint(String message) {
        System.err.println(message);
    }

    /**
     * 截断过长的文本用于显示
     */
    private static String truncateForDisplay(String text, int maxLength) {
        if (text == null) return "null";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "... (" + text.length() + " chars total)";
    }

    /**
     * 缩进文本
     */
    private static String indent(String text, int spaces) {
        String prefix = " ".repeat(spaces);
        return prefix + text.replace("\n", "\n" + prefix);
    }
}
