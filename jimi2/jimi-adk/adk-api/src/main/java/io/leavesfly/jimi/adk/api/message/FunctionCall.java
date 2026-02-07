package io.leavesfly.jimi.adk.api.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a function call within a tool call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionCall {
    
    /**
     * Name of the function to call
     */
    private String name;
    
    /**
     * Arguments for the function (JSON string)
     */
    private String arguments;
    
    /**
     * Create a function call
     */
    public static FunctionCall of(String name, String arguments) {
        return new FunctionCall(name, arguments);
    }
}
