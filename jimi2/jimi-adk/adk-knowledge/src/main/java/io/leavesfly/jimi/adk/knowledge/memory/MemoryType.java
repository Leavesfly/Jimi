package io.leavesfly.jimi.adk.knowledge.memory;

/**
 * 记忆类型枚举
 * 统一管理所有类型的长期记忆
 */
public enum MemoryType {
    /**
     * 错误模式
     */
    ERROR_PATTERN,
    
    /**
     * 项目知识
     */
    PROJECT_INSIGHT,
    
    /**
     * 会话摘要
     */
    SESSION_SUMMARY,
    
    /**
     * 任务历史
     */
    TASK_HISTORY,
    
    /**
     * 任务模式
     */
    TASK_PATTERN,
    
    /**
     * 用户偏好
     */
    USER_PREFERENCE
}
