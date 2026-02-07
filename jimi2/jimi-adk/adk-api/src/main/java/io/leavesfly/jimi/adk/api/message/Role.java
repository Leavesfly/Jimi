package io.leavesfly.jimi.adk.api.message;

/**
 * Message role enumeration.
 */
public enum Role {
    /**
     * System message - sets the behavior of the assistant
     */
    SYSTEM,
    
    /**
     * User message - input from the user
     */
    USER,
    
    /**
     * Assistant message - response from the AI
     */
    ASSISTANT,
    
    /**
     * Tool message - result from tool execution
     */
    TOOL;
    
    /**
     * Convert to lowercase string for API compatibility
     */
    public String toLowercase() {
        return name().toLowerCase();
    }
    
    /**
     * Parse from string (case-insensitive)
     */
    public static Role fromString(String value) {
        if (value == null) {
            return null;
        }
        return Role.valueOf(value.toUpperCase());
    }
}
