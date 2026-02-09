package io.leavesfly.jimi.cli.shell;

import org.jline.utils.AttributedStyle;

/**
 * 颜色映射工具
 * 将字符串颜色名转换为JLine的AttributedStyle颜色代码
 */
public class ColorMapper {
    
    /**
     * 将颜色名称映射到JLine颜色代码
     */
    public static int mapColor(String colorName) {
        if (colorName == null || colorName.isEmpty()) {
            return AttributedStyle.WHITE;
        }
        
        return switch (colorName.toLowerCase()) {
            case "black" -> AttributedStyle.BLACK;
            case "red" -> AttributedStyle.RED;
            case "green" -> AttributedStyle.GREEN;
            case "yellow" -> AttributedStyle.YELLOW;
            case "blue" -> AttributedStyle.BLUE;
            case "magenta" -> AttributedStyle.MAGENTA;
            case "cyan" -> AttributedStyle.CYAN;
            case "white" -> AttributedStyle.WHITE;
            
            // Bright colors
            case "bright_black" -> AttributedStyle.BRIGHT + AttributedStyle.BLACK;
            case "bright_red" -> AttributedStyle.BRIGHT + AttributedStyle.RED;
            case "bright_green" -> AttributedStyle.BRIGHT + AttributedStyle.GREEN;
            case "bright_yellow" -> AttributedStyle.BRIGHT + AttributedStyle.YELLOW;
            case "bright_blue" -> AttributedStyle.BRIGHT + AttributedStyle.BLUE;
            case "bright_magenta" -> AttributedStyle.BRIGHT + AttributedStyle.MAGENTA;
            case "bright_cyan" -> AttributedStyle.BRIGHT + AttributedStyle.CYAN;
            case "bright_white" -> AttributedStyle.BRIGHT + AttributedStyle.WHITE;
            
            default -> AttributedStyle.WHITE;
        };
    }
    
    /**
     * 创建带颜色的样式
     */
    public static AttributedStyle createStyle(String colorName) {
        return AttributedStyle.DEFAULT.foreground(mapColor(colorName));
    }
    
    /**
     * 创建带颜色和粗体的样式
     */
    public static AttributedStyle createBoldStyle(String colorName) {
        return AttributedStyle.DEFAULT.foreground(mapColor(colorName)).bold();
    }
    
    /**
     * 创建带颜色和斜体的样式
     */
    public static AttributedStyle createItalicStyle(String colorName) {
        return AttributedStyle.DEFAULT.foreground(mapColor(colorName)).italic();
    }
    
    /**
     * 创建带颜色、粗体和斜体的样式
     */
    public static AttributedStyle createBoldItalicStyle(String colorName) {
        return AttributedStyle.DEFAULT.foreground(mapColor(colorName)).bold().italic();
    }
}
