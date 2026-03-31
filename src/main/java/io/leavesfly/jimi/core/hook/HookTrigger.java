package io.leavesfly.jimi.core.hook;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Hook 触发配置
 *
 * 定义 Hook 在何时何地被触发。
 * 对齐 Claude Code 标准：支持 matcher 正则匹配机制。
 *
 * Claude Code 使用 matcher 字段来过滤工具名、通知类型等，
 * Jimi 同时保留 tools 列表和 filePatterns 以兼容 YAML 配置风格。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HookTrigger {

    /**
     * 触发类型 (必需)
     */
    private HookType type;

    /**
     * 匹配器正则表达式 (可选，对齐 Claude Code)
     * 用于匹配工具名、通知类型、Agent 类型等。
     * 例如: "Write|Edit" 匹配 Write 和 Edit 工具
     */
    private String matcher;

    /**
     * 工具名称列表 (可选，兼容 YAML 配置风格)
     * 仅对 PRE_TOOL_USE 和 POST_TOOL_USE 类型有效
     * 为空表示匹配所有工具
     */
    @Builder.Default
    private List<String> tools = new ArrayList<>();

    /**
     * 文件模式列表 (可选)
     * 使用 glob 模式匹配文件, 如: *.java, src/**\/*.xml
     * 仅对工具操作文件时有效
     */
    @Builder.Default
    @JsonProperty("file_patterns")
    private List<String> filePatterns = new ArrayList<>();

    /**
     * Agent 名称 (可选)
     * 仅对 PRE_AGENT_SWITCH 和 POST_AGENT_SWITCH 类型有效
     * 为空表示匹配所有 Agent
     */
    private String agentName;

    /**
     * 错误类型模式 (可选)
     * 仅对 ON_ERROR 类型有效
     * 支持正则表达式匹配错误消息
     */
    private String errorPattern;

    /**
     * 检查给定值是否匹配 matcher 正则表达式
     */
    public boolean matchesValue(String value) {
        if (matcher == null || matcher.isEmpty()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        try {
            return Pattern.matches(matcher, value);
        } catch (PatternSyntaxException e) {
            return matcher.equals(value);
        }
    }

    /**
     * 验证配置有效性
     */
    public void validate() {
        if (type == null) {
            throw new IllegalArgumentException("Trigger type is required");
        }

        // 验证 matcher 正则表达式语法
        if (matcher != null && !matcher.isEmpty()) {
            try {
                Pattern.compile(matcher);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        "Invalid matcher pattern: " + matcher + " - " + e.getMessage());
            }
        }

        // 验证类型特定配置
        switch (type) {
            case PRE_TOOL_USE:
            case POST_TOOL_USE:
            case POST_TOOL_USE_FAILURE:
                // tools, matcher, filePatterns 都是可选的
                break;

            case PRE_AGENT_SWITCH:
            case POST_AGENT_SWITCH:
            case SUBAGENT_STOP:
                // agentName 和 matcher 是可选的
                break;

            case ON_ERROR:
                // errorPattern 是可选的
                break;

            case USER_PROMPT_SUBMIT:
            case SESSION_START:
            case SESSION_END:
            case NOTIFICATION:
            case STOP:
                // 这些类型不需要额外配置，matcher 可选
                break;

            default:
                throw new IllegalArgumentException("Unknown trigger type: " + type);
        }
    }
}
