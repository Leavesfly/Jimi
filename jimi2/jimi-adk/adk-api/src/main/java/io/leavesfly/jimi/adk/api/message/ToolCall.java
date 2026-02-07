package io.leavesfly.jimi.adk.api.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a tool call made by the assistant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {
    
    /**
     * Unique identifier for this tool call
     */
    private String id;
    
    /**
     * Type of the tool call (always "function" for now)
     */
    @Builder.Default
    private String type = "function";
    
    /**
     * The function to call
     */
    private FunctionCall function;
    
    /**
     * Create a tool call
     */
    public static ToolCall of(String id, String name, String arguments) {
        return ToolCall.builder()
                .id(id)
                .function(FunctionCall.of(name, arguments))
                .build();
    }
}
