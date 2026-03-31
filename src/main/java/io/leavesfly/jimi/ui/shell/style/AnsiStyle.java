package io.leavesfly.jimi.ui.shell.style;

/**
 * ANSI 颜色和样式常量
 * 用于终端输出的颜色控制
 */
public final class AnsiStyle {

    private AnsiStyle() {
        // 工具类禁止实例化
    }

    // 重置
    public static final String RESET = "\u001B[0m";

    // 前景色
    public static final String BLACK = "\u001B[30m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";
    public static final String GRAY = "\u001B[90m";

    // 亮色前景
    public static final String BRIGHT_BLACK = "\u001B[90m";
    public static final String BRIGHT_RED = "\u001B[91m";
    public static final String BRIGHT_GREEN = "\u001B[92m";
    public static final String BRIGHT_YELLOW = "\u001B[93m";
    public static final String BRIGHT_BLUE = "\u001B[94m";
    public static final String BRIGHT_MAGENTA = "\u001B[95m";
    public static final String BRIGHT_CYAN = "\u001B[96m";
    public static final String BRIGHT_WHITE = "\u001B[97m";

    // 背景色
    public static final String BG_BLACK = "\u001B[40m";
    public static final String BG_RED = "\u001B[41m";
    public static final String BG_GREEN = "\u001B[42m";
    public static final String BG_YELLOW = "\u001B[43m";
    public static final String BG_BLUE = "\u001B[44m";
    public static final String BG_MAGENTA = "\u001B[45m";
    public static final String BG_CYAN = "\u001B[46m";
    public static final String BG_WHITE = "\u001B[47m";

    // 样式
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    public static final String ITALIC = "\u001B[3m";
    public static final String UNDERLINE = "\u001B[4m";
    public static final String BLINK = "\u001B[5m";
    public static final String REVERSE = "\u001B[7m";
    public static final String HIDDEN = "\u001B[8m";
    public static final String STRIKETHROUGH = "\u001B[9m";

    /**
     * 包裹文本并添加颜色
     */
    public static String colored(String text, String color) {
        return color + text + RESET;
    }

    /**
     * 创建红色文本
     */
    public static String red(String text) {
        return RED + text + RESET;
    }

    /**
     * 创建绿色文本
     */
    public static String green(String text) {
        return GREEN + text + RESET;
    }

    /**
     * 创建蓝色文本
     */
    public static String blue(String text) {
        return BLUE + text + RESET;
    }

    /**
     * 创建黄色文本
     */
    public static String yellow(String text) {
        return YELLOW + text + RESET;
    }

    /**
     * 创建青色文本
     */
    public static String cyan(String text) {
        return CYAN + text + RESET;
    }

    /**
     * 创建灰色文本
     */
    public static String gray(String text) {
        return GRAY + text + RESET;
    }

    /**
     * 创建粗体文本
     */
    public static String bold(String text) {
        return BOLD + text + RESET;
    }
}
