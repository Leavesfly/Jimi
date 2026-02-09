package io.leavesfly.jimi.adk.api.exception;

/**
 * Agent 规范异常
 * 当 Agent 规范文件无效或解析失败时抛出
 */
public class AgentSpecException extends AdkException {
    
    public AgentSpecException(String message) {
        super(message);
    }
    
    public AgentSpecException(String message, Throwable cause) {
        super(message, cause);
    }
}
