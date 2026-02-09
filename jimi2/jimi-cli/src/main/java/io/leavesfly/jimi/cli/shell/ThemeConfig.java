package io.leavesfly.jimi.cli.shell;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Shell UI 主题配置
 * 定义各种UI元素的颜色方案
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThemeConfig {
    
    @JsonProperty("name")
    @Builder.Default
    private String name = "default";
    
    @JsonProperty("prompt_color")
    @Builder.Default
    private String promptColor = "green";
    
    @JsonProperty("thinking_color")
    @Builder.Default
    private String thinkingColor = "yellow";
    
    @JsonProperty("error_color")
    @Builder.Default
    private String errorColor = "red";
    
    @JsonProperty("success_color")
    @Builder.Default
    private String successColor = "green";
    
    @JsonProperty("status_color")
    @Builder.Default
    private String statusColor = "yellow";
    
    @JsonProperty("info_color")
    @Builder.Default
    private String infoColor = "blue";
    
    @JsonProperty("assistant_color")
    @Builder.Default
    private String assistantColor = "yellow";
    
    @JsonProperty("reasoning_color")
    @Builder.Default
    private String reasoningColor = "white";
    
    @JsonProperty("token_color")
    @Builder.Default
    private String tokenColor = "cyan";
    
    @JsonProperty("hint_color")
    @Builder.Default
    private String hintColor = "blue";
    
    @JsonProperty("banner_color")
    @Builder.Default
    private String bannerColor = "cyan";
    
    @JsonProperty("bold_prompt")
    @Builder.Default
    private boolean boldPrompt = true;
    
    @JsonProperty("italic_reasoning")
    @Builder.Default
    private boolean italicReasoning = true;
    
    public static ThemeConfig defaultTheme() {
        return ThemeConfig.builder().name("default").build();
    }
    
    public static ThemeConfig darkTheme() {
        return ThemeConfig.builder()
                .name("dark")
                .promptColor("cyan")
                .thinkingColor("magenta")
                .assistantColor("white")
                .reasoningColor("bright_black")
                .tokenColor("magenta")
                .hintColor("cyan")
                .bannerColor("magenta")
                .build();
    }
    
    public static ThemeConfig lightTheme() {
        return ThemeConfig.builder()
                .name("light")
                .promptColor("blue")
                .thinkingColor("magenta")
                .statusColor("blue")
                .infoColor("cyan")
                .assistantColor("black")
                .reasoningColor("bright_black")
                .tokenColor("blue")
                .hintColor("magenta")
                .bannerColor("blue")
                .boldPrompt(false)
                .italicReasoning(false)
                .build();
    }
    
    public static ThemeConfig getPresetTheme(String themeName) {
        if (themeName == null) {
            return defaultTheme();
        }
        return switch (themeName.toLowerCase()) {
            case "dark" -> darkTheme();
            case "light" -> lightTheme();
            default -> defaultTheme();
        };
    }
}
