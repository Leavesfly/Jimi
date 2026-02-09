package io.leavesfly.jimi.cli.shell.output;

import io.leavesfly.jimi.cli.shell.ColorMapper;
import io.leavesfly.jimi.cli.shell.ThemeConfig;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;

/**
 * 输出格式化器
 * 提供统一的终端输出格式化功能
 */
public class OutputFormatter {
    
    private final Terminal terminal;
    private ThemeConfig theme;
    
    public OutputFormatter(Terminal terminal) {
        this.terminal = terminal;
        this.theme = ThemeConfig.defaultTheme();
    }
    
    public OutputFormatter(Terminal terminal, ThemeConfig theme) {
        this.terminal = terminal;
        this.theme = theme != null ? theme : ThemeConfig.defaultTheme();
    }
    
    /**
     * 设置主题
     */
    public void setTheme(ThemeConfig theme) {
        this.theme = theme != null ? theme : ThemeConfig.defaultTheme();
    }
    
    /**
     * 打印普通文本
     */
    public void println(String text) {
        terminal.writer().println(text);
        terminal.flush();
    }
    
    /**
     * 打印空行
     */
    public void println() {
        terminal.writer().println();
        terminal.flush();
    }
    
    /**
     * 打印成功信息
     */
    public void printSuccess(String text) {
        AttributedStyle style = ColorMapper.createStyle(theme.getSuccessColor());
        terminal.writer().println(new AttributedString("✓ " + text, style).toAnsi());
        terminal.flush();
    }
    
    /**
     * 打印状态信息
     */
    public void printStatus(String text) {
        AttributedStyle style = ColorMapper.createStyle(theme.getStatusColor());
        terminal.writer().println(new AttributedString("ℹ " + text, style).toAnsi());
        terminal.flush();
    }
    
    /**
     * 打印错误信息
     */
    public void printError(String text) {
        AttributedStyle style = ColorMapper.createStyle(theme.getErrorColor());
        terminal.writer().println(new AttributedString("✗ " + text, style).toAnsi());
        terminal.flush();
    }
    
    /**
     * 打印信息
     */
    public void printInfo(String text) {
        AttributedStyle style = ColorMapper.createStyle(theme.getInfoColor());
        terminal.writer().println(new AttributedString("→ " + text, style).toAnsi());
        terminal.flush();
    }
    
    /**
     * 打印警告信息
     */
    public void printWarning(String text) {
        AttributedStyle style = ColorMapper.createStyle(theme.getStatusColor());
        terminal.writer().println(new AttributedString("⚠ " + text, style).toAnsi());
        terminal.flush();
    }
    
    /**
     * 打印带样式的文本
     */
    public void printStyled(String text, AttributedStyle style) {
        terminal.writer().println(new AttributedString(text, style).toAnsi());
        terminal.flush();
    }
    
    /**
     * 清屏
     */
    public void clearScreen() {
        terminal.puts(InfoCmp.Capability.clear_screen);
        terminal.flush();
    }
    
    /**
     * 刷新输出
     */
    public void flush() {
        terminal.flush();
    }
    
    /**
     * 获取终端实例
     */
    public Terminal getTerminal() {
        return terminal;
    }
}
