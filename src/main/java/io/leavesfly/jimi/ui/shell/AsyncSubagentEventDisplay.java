package io.leavesfly.jimi.ui.shell;

import io.leavesfly.jimi.config.info.ShellUIConfig;
import io.leavesfly.jimi.ui.notification.NotificationService;
import io.leavesfly.jimi.ui.shell.output.AssistantTextRenderer;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import io.leavesfly.jimi.wire.message.AsyncSubagentCompleted;
import io.leavesfly.jimi.wire.message.AsyncSubagentProgress;
import io.leavesfly.jimi.wire.message.AsyncSubagentStarted;
import io.leavesfly.jimi.wire.message.AsyncSubagentTrigger;
import lombok.extern.slf4j.Slf4j;
import org.jline.terminal.Terminal;

import java.time.Duration;

/**
 * 异步子代理事件展示器
 * 负责将异步子代理的生命周期事件（启动/进度/完成/触发）渲染到终端
 */
@Slf4j
public class AsyncSubagentEventDisplay {

    private final Terminal terminal;
    private final OutputFormatter outputFormatter;
    private final NotificationService notificationService;
    private final ShellUIConfig uiConfig;
    private final AssistantTextRenderer renderer;

    public AsyncSubagentEventDisplay(Terminal terminal,
                                     OutputFormatter outputFormatter,
                                     NotificationService notificationService,
                                     ShellUIConfig uiConfig,
                                     AssistantTextRenderer renderer) {
        this.terminal = terminal;
        this.outputFormatter = outputFormatter;
        this.notificationService = notificationService;
        this.uiConfig = uiConfig;
        this.renderer = renderer;
    }

    public void handleStarted(AsyncSubagentStarted message) {
        renderer.flushLineIfNeeded();
        terminal.writer().println();
        outputFormatter.printStatus(String.format(
                "🚀 异步子代理已启动: [%s] %s",
                message.getSubagentId(), message.getSubagentName()));
        outputFormatter.printInfo(String.format(
                "   模式: %s | 使用 /async status %s 查看状态",
                message.getMode(), message.getSubagentId()));
        terminal.writer().println();
        terminal.flush();
    }

    public void handleProgress(AsyncSubagentProgress message) {
        log.debug("Async subagent {} progress: {} (step {})",
                message.getSubagentId(), message.getProgressInfo(), message.getStepNumber());
        // 进度消息较频繁，仅记录 debug 日志，避免刷新干扰用户
    }

    public void handleCompleted(AsyncSubagentCompleted message) {
        renderer.flushLineIfNeeded();
        terminal.writer().println();

        String durationStr = formatDuration(message.getDuration());

        if (message.isSuccess()) {
            outputFormatter.printSuccess(String.format(
                    "✅ 异步子代理完成: [%s] (%s)", message.getSubagentId(), durationStr));
            String result = message.getResult();
            if (result != null && !result.isEmpty()) {
                String preview = result.length() > 100 ? result.substring(0, 100) + "..." : result;
                outputFormatter.printInfo("   结果: " + preview.replace("\n", " "));
            }
        } else {
            outputFormatter.printError(String.format(
                    "❌ 异步子代理失败: [%s] (%s)", message.getSubagentId(), durationStr));
            if (message.getResult() != null) {
                outputFormatter.printError("   错误: " + message.getResult());
            }
        }

        outputFormatter.printInfo(String.format(
                "   使用 /async result %s 查看完整结果", message.getSubagentId()));
        terminal.writer().println();
        terminal.flush();

        notificationService.notifyAsyncComplete(
                message.getSubagentId(), message.getResult(), message.isSuccess(), uiConfig);
        ringBellIfEnabled();
    }

    public void handleTrigger(AsyncSubagentTrigger message) {
        renderer.flushLineIfNeeded();
        terminal.writer().println();

        outputFormatter.printWarning(String.format("🔔 监控触发: [%s]", message.getSubagentId()));
        outputFormatter.printInfo(String.format("   匹配模式: %s", message.getMatchedPattern()));

        String content = message.getMatchedContent();
        if (content != null && !content.isEmpty()) {
            String preview = content.length() > 150 ? content.substring(0, 150) + "..." : content;
            outputFormatter.printInfo("   触发内容: " + preview.replace("\n", " "));
        }

        if (message.getTriggerTime() != null) {
            String timeStr = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(message.getTriggerTime());
            outputFormatter.printInfo("   触发时间: " + timeStr);
        }

        terminal.writer().println();
        terminal.flush();

        notificationService.notifyWatchTrigger(
                message.getSubagentId(), message.getMatchedPattern(), message.getMatchedContent(), uiConfig);
        ringBellIfEnabled();
    }

    private void ringBellIfEnabled() {
        if (uiConfig.isEnableNotificationSound()) {
            try {
                terminal.writer().write('\007'); // Bell
                terminal.flush();
            } catch (Exception e) {
                // 忽略提示音错误
            }
        }
    }

    private String formatDuration(Duration duration) {
        if (duration == null) return "unknown";
        long seconds = duration.getSeconds();
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return String.format("%dm%ds", seconds / 60, seconds % 60);
        return String.format("%dh%dm%ds", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }
}
