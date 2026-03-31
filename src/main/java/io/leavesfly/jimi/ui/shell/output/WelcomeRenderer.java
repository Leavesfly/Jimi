package io.leavesfly.jimi.ui.shell.output;

import io.leavesfly.jimi.client.EngineClient;
import io.leavesfly.jimi.config.info.ShellUIConfig;
import io.leavesfly.jimi.config.info.ThemeConfig;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import io.leavesfly.jimi.ui.shell.style.ColorMapper;
import lombok.extern.slf4j.Slf4j;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 欢迎信息和提示渲染器
 * 负责 Banner、Welcome 消息、Token 统计和快捷提示的显示
 */
@Slf4j
public class WelcomeRenderer {

    private final Terminal terminal;
    private final OutputFormatter outputFormatter;
    private final ShellUIConfig uiConfig;
    private final EngineClient engineClient;
    private ThemeConfig theme;

    // 快捷提示计数器
    private final AtomicInteger interactionCount;
    private final AtomicBoolean welcomeHintShown;
    private final AtomicBoolean inputHintShown;
    private final AtomicBoolean thinkingHintShown;

    public WelcomeRenderer(Terminal terminal,
                           OutputFormatter outputFormatter,
                           ShellUIConfig uiConfig,
                           ThemeConfig theme,
                           EngineClient engineClient) {
        this.terminal = terminal;
        this.outputFormatter = outputFormatter;
        this.uiConfig = uiConfig;
        this.theme = theme;
        this.engineClient = engineClient;
        this.interactionCount = new AtomicInteger(0);
        this.welcomeHintShown = new AtomicBoolean(false);
        this.inputHintShown = new AtomicBoolean(false);
        this.thinkingHintShown = new AtomicBoolean(false);
    }

    public void setTheme(ThemeConfig theme) {
        this.theme = theme;
    }

    /**
     * 增加交互计数
     */
    public int incrementInteractionCount() {
        return interactionCount.incrementAndGet();
    }

    /**
     * 获取交互计数
     */
    public int getInteractionCount() {
        return interactionCount.get();
    }

    /**
     * 打印欢迎信息
     */
    public void printWelcome() {
        outputFormatter.println("");
        printBanner();
        outputFormatter.println("");
        outputFormatter.printSuccess("Welcome to Jimi ");
        outputFormatter.printInfo("Type /help for available commands, or just start chatting!");
        outputFormatter.println("");

        // 显示欢迎快捷提示
        showShortcutsHint("welcome");
    }

    /**
     * 打印 Banner
     */
    public void printBanner() {
        // 获取版本信息
        String version = getVersionInfo();
        String javaVersion = System.getProperty("java.version");
        // 提取主版本号 (如 "17" from "17.0.1")
        String javaMajorVersion = javaVersion.split("\\.")[0];

        String banner = String.format("""
                                
                ╭──────────────────────────────────────────────╮
                │                                              │
                │        🤖  J I M I  %-24s │
                │                                              │
                │        Your AI Coding Companion              │
                │        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━      │
                │                                              │
                │  🔧 Code  💬 Chat  🧠 Think  ⚡ Fast         │
                │                                              │
                │  Java %s | Type /help to start              │
                ╰──────────────────────────────────────────────╯
                                
                """, version, javaMajorVersion);

        AttributedStyle style = ColorMapper.createBoldStyle(theme.getBannerColor());

        terminal.writer().println(new AttributedString(banner, style).toAnsi());
        terminal.flush();
    }

    /**
     * 获取版本信息
     */
    private String getVersionInfo() {
        // 尝试从 Manifest 读取版本号
        Package pkg = this.getClass().getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;

        // 如果无法从 Manifest 获取，返回默认版本
        if (version == null || version.isEmpty()) {
            version = "v0.1.0"; // 从 pom.xml 读取的默认版本
        } else {
            version = "v" + version;
        }

        return version;
    }

    /**
     * 显示Token消耗统计（在每个步骤结束时调用）
     */
    public void showTokenUsage(ChatCompletionResult.Usage usage) {
        if (!uiConfig.isShowTokenUsage() || usage == null) {
            return;
        }

        // 记录当前步骤的Token
        int stepTokens = usage.getTotalTokens();

        // 构建显示消息
        StringBuilder msg = new StringBuilder();
        msg.append("\n📊 Token: ");
        msg.append("本次 ").append(usage.getPromptTokens()).append("+").append(usage.getCompletionTokens());
        msg.append(" = ").append(stepTokens);

        // 如果有上下文Token总数，显示累计
        try {
            int totalTokens = engineClient.getTokenCount();
            if (totalTokens > 0) {
                msg.append(" | 总计 ").append(totalTokens);
            }
        } catch (Exception e) {
            // 忽略错误
        }

        // 使用主题Token颜色显示
        AttributedStyle style = ColorMapper.createStyle(theme.getTokenColor());
        terminal.writer().println(new AttributedString(msg.toString(), style).toAnsi());
        terminal.flush();
    }

    /**
     * 显示快捷提示
     *
     * @param hintType 提示类型：welcome, input, thinking, error
     */
    public void showShortcutsHint(String hintType) {
        if (!uiConfig.isShowShortcutsHint()) {
            return;
        }

        // 根据频率配置决定是否显示
        switch (uiConfig.getShortcutsHintFrequency()) {
            case "first_time" -> {
                if (!shouldShowFirstTimeHint(hintType)) {
                    return;
                }
            }
            case "periodic" -> {
                int count = interactionCount.get();
                int interval = uiConfig.getShortcutsHintInterval();
                if (count % interval != 0) {
                    return;
                }
            }
            default -> { /* always: do nothing, show hint */ }
        }

        // 显示对应类型的提示
        String hint = getHintForType(hintType);
        if (hint != null && !hint.isEmpty()) {
            terminal.writer().println();
            AttributedStyle style = ColorMapper.createStyle(theme.getHintColor());
            if (theme.isItalicReasoning()) {
                style = style.italic();
            }
            terminal.writer().println(new AttributedString(hint, style).toAnsi());
            terminal.flush();
        }
    }

    /**
     * 判断是否应该显示首次提示
     */
    private boolean shouldShowFirstTimeHint(String hintType) {
        return switch (hintType) {
            case "welcome" -> welcomeHintShown.compareAndSet(false, true);
            case "input" -> inputHintShown.compareAndSet(false, true);
            case "thinking" -> thinkingHintShown.compareAndSet(false, true);
            default -> true;
        };
    }

    /**
     * 根据类型获取提示内容
     */
    private String getHintForType(String hintType) {
        return switch (hintType) {
            case "error" -> "💡 提示: /reset 清空上下文 | /status 查看状态 | /history 查看历史";
            case "approval" -> "💡 快捷键: y (批准) | n (拒绝) | a (全部批准)";
            default -> null;
        };
    }
}
