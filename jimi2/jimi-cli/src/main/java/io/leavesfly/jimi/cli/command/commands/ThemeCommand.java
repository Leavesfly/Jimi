package io.leavesfly.jimi.cli.command.commands;

import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;

import java.util.Arrays;
import java.util.List;

/**
 * Theme 命令 - 主题切换
 * <p>
 * 管理 UI 主题偏好（简化版）
 * </p>
 *
 * @author Jimi2 Team
 */
public class ThemeCommand implements Command {

    private static final List<String> AVAILABLE_THEMES = Arrays.asList(
            "default", "dark", "light", "minimal", "matrix"
    );

    private String currentTheme;

    public ThemeCommand() {
        this("default");
    }

    public ThemeCommand(String initialTheme) {
        this.currentTheme = initialTheme;
    }

    @Override
    public String getName() {
        return "theme";
    }

    @Override
    public String getDescription() {
        return "Switch UI theme";
    }

    @Override
    public String getCategory() {
        return "system";
    }

    @Override
    public String getUsage() {
        return "/theme [name]";
    }

    @Override
    public void execute(CommandContext context) {
        var output = context.getOutput();

        if (context.getArgCount() == 0) {
            // 显示当前主题和可用主题
            showThemes(output);
            return;
        }

        String themeName = context.getArg(0).toLowerCase();

        if (!AVAILABLE_THEMES.contains(themeName)) {
            output.error("Invalid theme: " + themeName);
            output.println("");
            showThemes(output);
            return;
        }

        // 切换主题
        this.currentTheme = themeName;
        output.success("Theme switched to: " + themeName);
        output.println("");
        output.info("Note: Theme changes will take effect on next application restart");
    }

    private void showThemes(io.leavesfly.jimi.adk.api.command.CommandOutput output) {
        output.title("UI Themes");
        output.separator();
        output.println("Current theme: " + currentTheme);
        output.println("");
        output.println("Available themes:");
        output.println("  - default: Default theme (green prompt)");
        output.println("  - dark: Dark theme (cyan/magenta)");
        output.println("  - light: Light theme (blue)");
        output.println("  - minimal: Minimal theme (black & white)");
        output.println("  - matrix: Matrix theme (hacker style)");
        output.println("");
        output.info("Usage: /theme <name>");
    }

    public String getCurrentTheme() {
        return currentTheme;
    }
}
