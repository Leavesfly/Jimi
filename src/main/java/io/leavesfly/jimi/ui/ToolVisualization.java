package io.leavesfly.jimi.ui;

import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具执行可视化
 * 
 * 实时显示工具的执行状态和进度：
 * 1. 工具调用开始（带旋转动画）
 * 2. 工具执行中（实时更新参数）
 * 3. 工具执行完成（显示结果摘要）
 * 
 * 功能特性：
 * - 🔄 实时进度动画
 * - 📊 执行时间统计
 * - ✅/✗ 成功/失败标识
 * - 📝 结果摘要显示
 * - 🎨 彩色输出
 * 
 * @author 山泽
 */
@Slf4j
public class ToolVisualization {
    
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
            sb.append(AnsiColors.BLUE).append(toolName).append(AnsiColors.RESET);
            
            // 副标题（灰色）
            if (!subtitle.isEmpty()) {
                sb.append(AnsiColors.GRAY).append(": ").append(subtitle).append(AnsiColors.RESET);
            }
            
            return sb.toString();
        }
        
        private String renderFinished() {
            StringBuilder sb = new StringBuilder();
            
            // 成功/失败标识
            if (result.isOk()) {
                sb.append(AnsiColors.GREEN).append("✓").append(AnsiColors.RESET);
            } else {
                sb.append(AnsiColors.RED).append("✗").append(AnsiColors.RESET);
            }
            sb.append(" ");
            
            // 工具名称
            sb.append("Used ").append(AnsiColors.BLUE).append(toolName).append(AnsiColors.RESET);
            
            // 副标题
            if (!subtitle.isEmpty()) {
                sb.append(AnsiColors.GRAY).append(": ").append(subtitle).append(AnsiColors.RESET);
            }
            
            // 执行时间
            long millis = getDuration().toMillis();
            sb.append(AnsiColors.GRAY).append(" (").append(millis).append("ms)").append(AnsiColors.RESET);
            
            return sb.toString();
        }
        
        private String getSpinner() {
            // 简单的旋转动画
            String[] frames = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
            long elapsed = getDuration().toMillis();
            int index = (int) ((elapsed / 80) % frames.length);
            return AnsiColors.CYAN + frames[index] + AnsiColors.RESET;
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
            
            // 显示结果摘要
            if (result.getBrief() != null && !result.getBrief().isEmpty()) {
                String style = result.isOk() ? AnsiColors.GRAY : AnsiColors.RED;
                System.out.println("  " + style + result.getBrief() + AnsiColors.RESET);
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
            case "ReadFile", "WriteFile", "StrReplaceFile", "PatchFile" -> 
                extractJsonField(arguments, "path");
            case "Bash" -> 
                extractJsonField(arguments, "command");
            case "SearchWeb" -> 
                extractJsonField(arguments, "query");
            case "FetchURL" -> 
                extractJsonField(arguments, "url");
            case "Task" -> 
                extractJsonField(arguments, "description");
            case "Think" -> 
                extractJsonField(arguments, "thought");
            default -> null;
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
                // 限制长度
                if (value.length() > 50) {
                    value = value.substring(0, 47) + "...";
                }
                return value;
            }
        } catch (Exception e) {
            log.trace("Failed to extract field {} from JSON", fieldName, e);
        }
        return null;
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
    
    /**
     * ANSI 颜色代码
     */
    public static class AnsiColors {
        public static final String RESET = "\u001B[0m";
        public static final String RED = "\u001B[31m";
        public static final String GREEN = "\u001B[32m";
        public static final String BLUE = "\u001B[34m";
        public static final String CYAN = "\u001B[36m";
        public static final String GRAY = "\u001B[90m";
        
        // 粗体
        public static final String BOLD = "\u001B[1m";
        
        // 背景色
        public static final String BG_RED = "\u001B[41m";
        public static final String BG_GREEN = "\u001B[42m";
    }
}
