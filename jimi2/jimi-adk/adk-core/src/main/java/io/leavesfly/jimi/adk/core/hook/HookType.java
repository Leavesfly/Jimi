package io.leavesfly.jimi.adk.core.hook;

/**
 * Hook 类型枚举
 * 定义系统中可用的 Hook 触发点
 */
public enum HookType {
    
    /** 用户输入前 */
    PRE_USER_INPUT,
    
    /** 用户输入后 */
    POST_USER_INPUT,
    
    /** 工具调用前 */
    PRE_TOOL_CALL,
    
    /** 工具调用后 */
    POST_TOOL_CALL,
    
    /** Agent 切换前 */
    PRE_AGENT_SWITCH,
    
    /** Agent 切换后 */
    POST_AGENT_SWITCH,
    
    /** 错误发生时 */
    ON_ERROR,
    
    /** 会话启动时 */
    ON_SESSION_START,
    
    /** 会话结束时 */
    ON_SESSION_END
}
