package io.leavesfly.jimi.adk.api.tool;

import io.leavesfly.jimi.adk.api.agent.AgentSpec;
import io.leavesfly.jimi.adk.api.engine.Runtime;

import java.util.List;

/**
 * Tool provider SPI interface.
 * Implementations provide tools based on agent configuration and runtime context.
 */
public interface ToolProvider {
    
    /**
     * Check if this provider supports the given agent and runtime.
     *
     * @param agentSpec agent specification
     * @param runtime runtime context
     * @return true if this provider can provide tools
     */
    boolean supports(AgentSpec agentSpec, Runtime runtime);
    
    /**
     * Create tools for the given agent and runtime.
     *
     * @param agentSpec agent specification
     * @param runtime runtime context
     * @return list of tools
     */
    List<Tool<?>> createTools(AgentSpec agentSpec, Runtime runtime);
    
    /**
     * Get the loading order.
     * Lower values are loaded first.
     *
     * @return loading order
     */
    default int getOrder() {
        return 100;
    }
    
    /**
     * Get the provider name (for logging).
     *
     * @return provider name
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}
