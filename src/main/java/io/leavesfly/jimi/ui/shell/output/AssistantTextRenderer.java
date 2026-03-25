package io.leavesfly.jimi.ui.shell.output;

import io.leavesfly.jimi.config.info.ThemeConfig;
import io.leavesfly.jimi.ui.shell.ColorMapper;
import lombok.extern.slf4j.Slf4j;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 助手文本渲染器
 * 负责流式文本的输出、智能换行和推理模式切换标记
 * 持有助手输出状态，供其他组件调用 flushLineIfNeeded() 确保换行完整
 */
@Slf4j
public class AssistantTextRenderer {

    private final Terminal terminal;
    private ThemeConfig theme;

    private final AtomicBoolean outputStarted = new AtomicBoolean(false);
    private final AtomicInteger currentLineLength = new AtomicInteger(0);
    private final AtomicBoolean isInReasoningMode = new AtomicBoolean(false);

    public AssistantTextRenderer(Terminal terminal, ThemeConfig theme) {
        this.terminal = terminal;
        this.theme = theme;
    }

    public void setTheme(ThemeConfig theme) {
        this.theme = theme;
    }

    /**
     * 如果助手输出正在进行中，则换行并重置状态
     * 在输出其他类型消息前调用，确保当前行已完成
     */
    public void flushLineIfNeeded() {
        if (outputStarted.getAndSet(false)) {
            terminal.writer().println();
            terminal.flush();
        }
    }

    /**
     * 在新步骤开始时重置输出状态
     */
    public void resetForNewStep() {
        outputStarted.set(false);
        currentLineLength.set(0);
        isInReasoningMode.set(false);
    }

    /**
     * 打印助手文本（流式，带智能换行和推理模式切换标记）
     *
     * @param text        要打印的文本片段
     * @param isReasoning 是否为推理内容（思考过程）
     */
    public void print(String text, boolean isReasoning) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if ("null".equals(text)) {
            log.warn("Received 'null' string as content, ignoring");
            return;
        }

        // 第一次输出时换行
        if (!outputStarted.getAndSet(true)) {
            terminal.writer().println();
            terminal.flush();
            currentLineLength.set(0);
        }

        // 检查是否需要切换推理/正式内容模式
        if (isReasoning != isInReasoningMode.get()) {
            if (currentLineLength.get() > 0) {
                terminal.writer().println();
                currentLineLength.set(0);
            }
            if (isReasoning) {
                AttributedStyle labelStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).italic();
                terminal.writer().println(new AttributedString("💡 [思考过程]", labelStyle).toAnsi());
            } else {
                terminal.writer().println();
                AttributedStyle labelStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold();
                terminal.writer().println(new AttributedString("✅ [正式回答]", labelStyle).toAnsi());
            }
            terminal.flush();
            currentLineLength.set(0);
            isInReasoningMode.set(isReasoning);
        }

        int maxLineWidth = resolveMaxLineWidth();
        AttributedStyle style = resolveStyle(isReasoning);

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (ch == '\n') {
                terminal.writer().println();
                currentLineLength.set(0);
                continue;
            }

            int charWidth = isChineseChar(ch) ? 2 : 1;
            if (currentLineLength.get() + charWidth > maxLineWidth) {
                terminal.writer().println();
                currentLineLength.set(0);
                if (ch == ' ') continue;
            }

            terminal.writer().print(new AttributedString(String.valueOf(ch), style).toAnsi());
            currentLineLength.addAndGet(charWidth);
        }

        terminal.flush();
    }

    private int resolveMaxLineWidth() {
        int terminalWidth = terminal.getWidth();
        return terminalWidth > 20 ? terminalWidth - 4 : 76;
    }

    private AttributedStyle resolveStyle(boolean isReasoning) {
        if (isReasoning) {
            AttributedStyle style = ColorMapper.createStyle(theme.getReasoningColor());
            return theme.isItalicReasoning() ? style.italic() : style;
        }
        return ColorMapper.createStyle(theme.getAssistantColor());
    }

    private boolean isChineseChar(char ch) {
        return ch >= 0x4E00 && ch <= 0x9FA5;
    }
}
