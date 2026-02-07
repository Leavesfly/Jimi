package io.leavesfly.jimi.adk.api.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tool execution result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {
    
    /**
     * Whether the execution was successful
     */
    private boolean ok;
    
    /**
     * Result message or content
     */
    private String message;
    
    /**
     * Error details (if any)
     */
    private String error;
    
    /**
     * Result data (structured data if needed)
     */
    private Object data;
    
    /**
     * Create a successful result
     */
    public static ToolResult success(String message) {
        return ToolResult.builder()
                .ok(true)
                .message(message)
                .build();
    }
    
    /**
     * Create a successful result with data
     */
    public static ToolResult success(String message, Object data) {
        return ToolResult.builder()
                .ok(true)
                .message(message)
                .data(data)
                .build();
    }
    
    /**
     * Create an error result
     */
    public static ToolResult error(String message) {
        return ToolResult.builder()
                .ok(false)
                .error(message)
                .message(message)
                .build();
    }
    
    /**
     * Create an error result with detailed message
     */
    public static ToolResult error(String message, String error) {
        return ToolResult.builder()
                .ok(false)
                .message(message)
                .error(error)
                .build();
    }
}
