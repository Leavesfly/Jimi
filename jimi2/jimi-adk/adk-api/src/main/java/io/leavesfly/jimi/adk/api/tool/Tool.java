package io.leavesfly.jimi.adk.api.tool;

import reactor.core.publisher.Mono;

/**
 * Tool interface - base interface for all tools.
 * Tools are executable actions that the AI agent can invoke.
 *
 * @param <P> Parameter type for the tool
 */
public interface Tool<P> {
    
    /**
     * Get the tool name.
     * Must be unique within a registry.
     *
     * @return tool name
     */
    String getName();
    
    /**
     * Get the tool description.
     * Used by LLM to understand when to use the tool.
     *
     * @return tool description
     */
    String getDescription();
    
    /**
     * Get the parameter type class.
     * Used for JSON deserialization.
     *
     * @return parameter class
     */
    Class<P> getParamsType();
    
    /**
     * Execute the tool with given parameters.
     *
     * @param params tool parameters
     * @return execution result as Mono
     */
    Mono<ToolResult> execute(P params);
    
    /**
     * Validate parameters before execution.
     * Override to provide custom validation.
     *
     * @param params tool parameters
     * @return true if valid
     */
    default boolean validateParams(P params) {
        return true;
    }
    
    /**
     * Check if this tool requires approval before execution.
     * Override to require user confirmation for dangerous operations.
     *
     * @return true if approval is required
     */
    default boolean requiresApproval() {
        return false;
    }
    
    /**
     * Get the approval description for this tool.
     * Used when displaying approval request to user.
     *
     * @param params tool parameters
     * @return approval description
     */
    default String getApprovalDescription(P params) {
        return "Execute " + getName();
    }
}
