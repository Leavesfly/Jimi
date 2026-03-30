package io.leavesfly.jimi.core.hook;

/**
 * Hook 类型枚举
 * 
 * 定义系统中可用的 Hook 触发点
 */
public enum HookType {
    
    /**
     * 用户输入前
     * 触发时机: 用户输入被处理之前
     * 用途: 输入预处理、上下文准备
     */
    PRE_USER_INPUT,
    
    /**
     * 用户输入后
     * 触发时机: 用户输入被处理之后
     * 用途: 输入验证、自动补全
     */
    POST_USER_INPUT,
    
    /**
     * 工具调用前
     * 触发时机: 工具执行之前
     * 用途: 权限检查、参数验证、审批
     */
    PRE_TOOL_CALL,
    
    /**
     * 工具调用后
     * 触发时机: 工具执行成功之后
     * 用途: 自动格式化、提交、清理
     */
    POST_TOOL_CALL,
    
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
    ON_ERROR,
    
    /**
     * 会话启动时
     * 触发时机: Jimi 会话启动
     * 用途: 环境初始化、配置加载
     */
    ON_SESSION_START,
    
    /**
     * 会话结束时
     * 触发时机: Jimi 会话结束
     * 用途: 资源清理、状态保存
     */
    ON_SESSION_END
}
