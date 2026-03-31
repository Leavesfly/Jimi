package io.leavesfly.jimi.ui.shell.style;

import io.leavesfly.jimi.config.info.ShellUIConfig;
import io.leavesfly.jimi.config.info.ThemeConfig;
import io.leavesfly.jimi.client.EngineClient;
import lombok.extern.slf4j.Slf4j;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 提示符构建器
 * 根据当前状态和配置构建 simple / normal / rich 三种风格的提示符
 */
@Slf4j
public class PromptBuilder {

    private final AtomicReference<String> currentStatus;
    private final ShellUIConfig uiConfig;
    private final EngineClient engineClient;
    private ThemeConfig theme;

    public PromptBuilder(AtomicReference<String> currentStatus,
                         ShellUIConfig uiConfig,
                         ThemeConfig theme,
                         EngineClient engineClient) {
        this.currentStatus = currentStatus;
        this.uiConfig = uiConfig;
        this.theme = theme;
        this.engineClient = engineClient;
    }

    public void setTheme(ThemeConfig theme) {
        this.theme = theme;
    }

    /**
     * 根据配置的样式构建提示符字符串
     */
    public String build() {
        return switch (uiConfig.getPromptStyle()) {
            case "simple" -> buildSimple();
            case "rich" -> buildRich();
            default -> buildNormal();
        };
    }

    private String buildSimple() {
        String status = currentStatus.get();
        return new AttributedString(getIconForStatus(status) + " jimi> ",
                getStyleForStatus(status)).toAnsi();
    }

    private String buildNormal() {
        String status = currentStatus.get();
        StringBuilder promptText = new StringBuilder();
        promptText.append(getIconForStatus(status)).append(" jimi");
        if ("compacting".equals(status)) {
            promptText.append("[🗂️]");
        }
        promptText.append("> ");
        return new AttributedString(promptText.toString(), getStyleForStatus(status)).toAnsi();
    }

    private String buildRich() {
        String status = currentStatus.get();
        StringBuilder promptText = new StringBuilder();

        if (uiConfig.isShowTimeInPrompt()) {
            String time = java.time.LocalTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            promptText.append("[").append(time).append("] ");
        }

        promptText.append(getIconForStatus(status)).append(" jimi");

        if (status.startsWith("thinking")) {
            promptText.append("[🧠]");
        } else if (status.equals("compacting")) {
            promptText.append("[🗂️]");
        } else if (status.equals("interrupted")) {
            promptText.append("[⚠️]");
        } else if (status.equals("error")) {
            promptText.append("[❌]");
        }

        if (uiConfig.isShowContextStats()) {
            try {
                int messageCount = engineClient.getHistorySize();
                int tokenCount = engineClient.getTokenCount();
                promptText.append(" [💬").append(messageCount);
                if (tokenCount > 0) {
                    promptText.append(" 💡");
                    if (tokenCount >= 1000) {
                        promptText.append(String.format("%.1fK", tokenCount / 1000.0));
                    } else {
                        promptText.append(tokenCount);
                    }
                }
                promptText.append("]");
            } catch (Exception e) {
                log.warn("Failed to get context stats", e);
            }
        }

        promptText.append("> ");
        return new AttributedString(promptText.toString(), getStyleForStatus(status)).toAnsi();
    }

    private String getIconForStatus(String status) {
        if (status.startsWith("thinking")) return "🧠";
        return switch (status) {
            case "compacting" -> "🗂️";
            case "interrupted" -> "⚠️";
            case "error" -> "❌";
            default -> "✨";
        };
    }

    private AttributedStyle getStyleForStatus(String status) {
        if (status.startsWith("thinking")) {
            AttributedStyle style = ColorMapper.createStyle(theme.getThinkingColor());
            return theme.isBoldPrompt() ? style.bold() : style;
        }
        return switch (status) {
            case "compacting" -> ColorMapper.createStyle(theme.getStatusColor());
            case "interrupted", "error" -> ColorMapper.createStyle(theme.getErrorColor());
            default -> {
                AttributedStyle readyStyle = ColorMapper.createStyle(theme.getPromptColor());
                yield theme.isBoldPrompt() ? readyStyle.bold() : readyStyle;
            }
        };
    }
}
