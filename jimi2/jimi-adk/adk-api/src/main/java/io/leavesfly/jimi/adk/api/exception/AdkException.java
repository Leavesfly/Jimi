package io.leavesfly.jimi.adk.api.exception;

/**
 * ADK 基础异常类
 * 所有 ADK 相关异常的基类
 */
public class AdkException extends RuntimeException {
    
    public AdkException(String message) {
        super(message);
    }
    
    public AdkException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public AdkException(Throwable cause) {
        super(cause);
    }
}
