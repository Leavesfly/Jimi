package io.leavesfly.jimi.core.hook;

/**
 * Hook 类型枚举
 *
 * 定义系统中可用的 Hook 触发点。
 * 对齐 Claude Code 标准事件类型，同时保留 Jimi 特有的扩展。
 *
 * Claude Code 标准事件:
 * - PreToolUse / PostToolUse / PostToolUseFailure
 * - UserPromptSubmit
 * - Stop / SubagentStop
 * - SessionStart / SessionEnd
 * - Notification
 */
public enum HookType {

    /**
     * 用户输入提交时（对齐 Claude Code: UserPromptSubmit）
     * 触发时机: 用户输入被处理之前
     * 用途: 输入预处理、上下文准备、输入验证
     */
    USER_PROMPT_SUBMIT,

    /**
     * 工具调用前（对齐 Claude Code: PreToolUse）
     * 触发时机: 工具执行之前
     * 用途: 权限检查、参数验证、审批
     */
    PRE_TOOL_USE,

    /**
     * 工具调用后（对齐 Claude Code: PostToolUse）
     * 触发时机: 工具执行成功之后
     * 用途: 自动格式化、提交、清理
     */
    POST_TOOL_USE,

    /**
     * 工具调用失败后（对齐 Claude Code: PostToolUseFailure）
     * 触发时机: 工具执行失败之后
     * 用途: 错误日志、告警、自动修复
     */
    POST_TOOL_USE_FAILURE,

    /**
     * 通知事件（对齐 Claude Code: Notification）
     * 触发时机: 系统发送通知时
     * 用途: 自定义通知处理、外部集成
     */
    NOTIFICATION,

    /**
     * 停止事件（对齐 Claude Code: Stop）
     * 触发时机: Agent 完成响应准备停止时
     * 用途: 验证任务完成度、强制继续
     */
    STOP,

    /**
     * 子代理停止事件（对齐 Claude Code: SubagentStop）
     * 触发时机: 子代理完成响应时
     * 用途: 验证子任务完成度
     */
    SUBAGENT_STOP,

    /**
     * 会话启动时（对齐 Claude Code: SessionStart）
     * 触发时机: Jimi 会话启动
     * 用途: 环境初始化、配置加载、上下文准备
     */
    SESSION_START,

    /**
     * 会话结束时（对齐 Claude Code: SessionEnd）
     * 触发时机: Jimi 会话结束
     * 用途: 资源清理、状态保存
     */
    SESSION_END,

    // ===== Jimi 扩展事件 =====

    /**
     * Agent 切换前
     * 触发时机: Agent 切换之前
     * 用途: 保存状态、清理资源
     */
    PRE_AGENT_SWITCH,

    /**
     * Agent 切换后
     * 触发时机: Agent 切换之后
     * 用途: 加载配置、初始化环境
     */
    POST_AGENT_SWITCH,

    /**
     * 错误发生时
     * 触发时机: 系统捕获到错误
     * 用途: 错误处理、日志记录、自动修复
     */
    ON_ERROR;

    /**
     * 从字符串解析 HookType（支持多种命名风格）
     */
    public static HookType fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Hook type value is required");
        }

        String normalized = value.trim().toUpperCase().replace("-", "_");

        // 支持 Claude Code 风格的命名（如 "PreToolUse" -> PRE_TOOL_USE）
        String camelConverted = value.trim()
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toUpperCase();

        for (HookType hookType : values()) {
            if (hookType.name().equals(normalized) || hookType.name().equals(camelConverted)) {
                return hookType;
            }
        }

        // 兼容旧版命名
        return switch (normalized) {
            case "PRE_USER_INPUT" -> USER_PROMPT_SUBMIT;
            case "POST_USER_INPUT" -> USER_PROMPT_SUBMIT;
            case "PRE_TOOL_CALL" -> PRE_TOOL_USE;
            case "POST_TOOL_CALL" -> POST_TOOL_USE;
            case "ON_SESSION_START" -> SESSION_START;
            case "ON_SESSION_END" -> SESSION_END;
            default -> throw new IllegalArgumentException("Unknown hook type: " + value);
        };
    }
}
