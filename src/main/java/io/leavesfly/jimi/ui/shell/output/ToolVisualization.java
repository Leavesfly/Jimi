package io.leavesfly.jimi.ui.shell.output;

import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.ui.shell.style.AnsiStyle;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具执行可视化
 * <p>
 * 实时显示工具的执行状态和进度：
 * 1. 工具调用开始（带旋转动画）
 * 2. 工具执行中（实时更新参数）
 * 3. 工具执行完成（显示结果摘要）
 * <p>
 * 功能特性：
 * - 实时进度动画
 * - 执行时间统计
 * - 成功/失败标识
 * - 结果摘要显示
 * - 彩色输出
 */
@Slf4j
public class ToolVisualization {

    // 配置常量
    private static final int MAX_SUBTITLE_LENGTH = 30;  // 副标题最大长度
    private static final int MAX_BRIEF_LENGTH = 50;     // 结果摘要最大长度

    private final Map<String, ToolCallDisplay> activeTools = new HashMap<>();
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    /**
     * 工具调用显示状态
     */
    private static class ToolCallDisplay {
        private final String toolName;
        private final Instant startTime;
        private String subtitle = "";
        private boolean finished = false;
        private ToolResult result;

        public ToolCallDisplay(String toolName) {
            this.toolName = toolName;
            this.startTime = Instant.now();
        }

        public void updateSubtitle(String subtitle) {
            this.subtitle = subtitle;
        }

        public void finish(ToolResult result) {
            this.finished = true;
            this.result = result;
        }

        public boolean isFinished() {
            return finished;
        }

        public Duration getDuration() {
            return Duration.between(startTime, Instant.now());
        }

        public String render() {
            if (finished) {
                return renderFinished();
            } else {
                return renderInProgress();
            }
        }

        private String renderInProgress() {
            StringBuilder sb = new StringBuilder();

            // 旋转动画
            sb.append(getSpinner());
            sb.append(" ");

            // 工具名称（蓝色）
            sb.append(AnsiStyle.BLUE).append(toolName).append(AnsiStyle.RESET);

            // 副标题（灰色）
            if (!subtitle.isEmpty()) {
                sb.append(AnsiStyle.GRAY).append(": ").append(subtitle).append(AnsiStyle.RESET);
            }

            return sb.toString();
        }

        private String renderFinished() {
            StringBuilder sb = new StringBuilder();

            // 成功/失败标识
            if (result.isOk()) {
                sb.append(AnsiStyle.GREEN).append("✓").append(AnsiStyle.RESET);
            } else {
                sb.append(AnsiStyle.RED).append("✗").append(AnsiStyle.RESET);
            }
            sb.append(" ");

            // 工具名称
            sb.append("Used ").append(AnsiStyle.BLUE).append(toolName).append(AnsiStyle.RESET);

            // 副标题
            if (!subtitle.isEmpty()) {
                sb.append(AnsiStyle.GRAY).append(": ").append(subtitle).append(AnsiStyle.RESET);
            }

            // 执行时间
            long millis = getDuration().toMillis();
            sb.append(AnsiStyle.GRAY).append(" (").append(millis).append("ms)").append(AnsiStyle.RESET);

            return sb.toString();
        }

        private String getSpinner() {
            // 简单的旋转动画
            String[] frames = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
            long elapsed = getDuration().toMillis();
            int index = (int) ((elapsed / 80) % frames.length);
            return AnsiStyle.CYAN + frames[index] + AnsiStyle.RESET;
        }
    }

    /**
     * 开始显示工具调用
     */
    public void onToolCallStart(ToolCall toolCall) {
        if (!enabled.get()) {
            return;
        }

        String toolName = toolCall.getFunction().getName();
        String toolCallId = toolCall.getId();

        ToolCallDisplay display = new ToolCallDisplay(toolName);

        // 从参数中提取副标题
        String subtitle = extractSubtitle(toolCall);
        if (subtitle != null) {
            display.updateSubtitle(subtitle);
        }

        activeTools.put(toolCallId, display);

        // 打印初始状态
        System.out.println(display.render());

        log.debug("Tool call started: {} ({})", toolName, toolCallId);
    }

    /**
     * 更新工具调用（增量参数）
     */
    public void onToolCallUpdate(String toolCallId, String argumentsDelta) {
        if (!enabled.get()) {
            return;
        }

        ToolCallDisplay display = activeTools.get(toolCallId);
        if (display != null && !display.isFinished()) {
            // 可以在这里更新副标题（如果需要实时解析参数）
            // 但为了性能，我们暂时跳过
            log.trace("Tool call updated: {}", toolCallId);
        }
    }

    /**
     * 完成工具调用
     */
    public void onToolCallComplete(String toolCallId, ToolResult result) {
        if (!enabled.get()) {
            return;
        }

        ToolCallDisplay display = activeTools.get(toolCallId);
        if (display != null) {
            display.finish(result);

            // 清除之前的行并打印完成状态
            System.out.print("\r\033[K");  // 清除当前行
            System.out.println(display.render());

            // 显示结果摘要（只显示成功时的摘要，失败时不显示异常日志给用户）
            if (result.isOk() && result.getBrief() != null && !result.getBrief().isEmpty()) {
                String brief = truncateText(result.getBrief(), MAX_BRIEF_LENGTH);
                System.out.println("  " + AnsiStyle.GRAY + brief + AnsiStyle.RESET);
            }

            // 从活动列表中移除
            activeTools.remove(toolCallId);

            log.debug("Tool call completed: {} ({}ms)",
                    display.toolName, display.getDuration().toMillis());
        }
    }

    /**
     * 从工具调用中提取副标题
     * 解析参数 JSON 并提取关键信息
     */
    private String extractSubtitle(ToolCall toolCall) {
        String toolName = toolCall.getFunction().getName();
        String arguments = toolCall.getFunction().getArguments();

        if (arguments == null || arguments.isEmpty()) {
            return null;
        }

        // 根据工具类型提取不同的信息
        return switch (toolName) {
            case "ReadFile", "WriteFile", "StrReplaceFile" -> extractJsonField(arguments, "path");
            case "BashTool" -> extractJsonField(arguments, "command");
            case "SearchWeb" -> extractJsonField(arguments, "query");
            case "FetchURL" -> extractJsonField(arguments, "url");
            case "SubAgentTool" -> extractJsonField(arguments, "description");
            case "Think" -> extractJsonField(arguments, "thought");
            case "Glob" -> extractJsonField(arguments, "pattern");
            default -> extractJsonField(arguments, "command");
        };
    }

    /**
     * 从 JSON 字符串中提取字段值（简单实现）
     */
    private String extractJsonField(String json, String fieldName) {
        try {
            // 简单的 JSON 字段提取（不使用完整的 JSON 解析器以提高性能）
            String pattern = "\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"";
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(json);
            if (m.find()) {
                String value = m.group(1);
                return truncateText(value, MAX_SUBTITLE_LENGTH);
            }
        } catch (Exception e) {
            log.trace("Failed to extract field {} from JSON", fieldName, e);
        }
        return null;
    }

    /**
     * 智能截取文本
     * - 优先在单词边界截断（英文）
     * - 优先在标点符号处截断（中文）
     * - 保持路径的关键部分可见
     */
    private String truncateText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }

        // 对于路径，优先保留文件名
        if (text.contains("/") || text.contains("\\")) {
            return truncatePath(text, maxLength);
        }

        // 对于普通文本，智能截断
        return truncateNormalText(text, maxLength);
    }

    /**
     * 截取路径，优先保留文件名
     */
    private String truncatePath(String path, int maxLength) {
        if (path.length() <= maxLength) {
            return path;
        }

        // 分离目录和文件名
        int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (lastSep > 0) {
            String filename = path.substring(lastSep + 1);
            String dir = path.substring(0, lastSep);

            // 如果文件名本身就太长，直接截断
            if (filename.length() >= maxLength - 5) {
                return "..." + filename.substring(filename.length() - (maxLength - 3));
            }

            // 否则保留文件名，缩短目录部分
            int remainingLength = maxLength - filename.length() - 4; // 4 = "..." + "/"
            if (remainingLength > 0 && dir.length() > remainingLength) {
                return dir.substring(0, remainingLength) + ".../" + filename;
            } else if (remainingLength <= 0) {
                return "..." + filename;
            }
        }

        // 如果没有分隔符，直接从末尾截取
        return "..." + path.substring(path.length() - (maxLength - 3));
    }

    /**
     * 截取普通文本，尽量在合适的位置断开
     */
    private String truncateNormalText(String text, int maxLength) {
        int cutPoint = maxLength - 3; // 留出 "..." 的空间

        // 尝试在空格处截断
        int lastSpace = text.lastIndexOf(' ', cutPoint);
        if (lastSpace > cutPoint - 10 && lastSpace > 0) {
            return text.substring(0, lastSpace) + "...";
        }

        // 尝试在标点符号处截断
        String punctuation = ",.;:!?，。；：！？";
        for (int i = cutPoint; i >= cutPoint - 10 && i >= 0; i--) {
            if (punctuation.indexOf(text.charAt(i)) >= 0) {
                return text.substring(0, i + 1) + "...";
            }
        }

        // 否则直接截断
        return text.substring(0, cutPoint) + "...";
    }

    /**
     * 启用/禁用可视化
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    /**
     * 清理所有活动的工具显示
     */
    public void cleanup() {
        activeTools.clear();
    }
}
