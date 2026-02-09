package io.leavesfly.jimi.adk.api.exception;

/**
 * 工具执行异常
 * 当工具执行失败时抛出
 */
public class ToolExecutionException extends AdkException {
    
    private final String toolName;
    
    public ToolExecutionException(String toolName, String message) {
        super(String.format("Tool '%s' execution failed: %s", toolName, message));
        this.toolName = toolName;
    }
    
    public ToolExecutionException(String toolName, String message, Throwable cause) {
        super(String.format("Tool '%s' execution failed: %s", toolName, message), cause);
        this.toolName = toolName;
    }
    
    public String getToolName() {
        return toolName;
    }
}
