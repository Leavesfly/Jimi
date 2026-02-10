package io.leavesfly.jimi.adk.api.tool;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Registry for tools.
 * Manages tool registration, lookup, and execution.
 */
public interface ToolRegistry {

    /**
     * Register a tool.
     */
    void register(Tool<?> tool);

    /**
     * Register multiple tools.
     */
    void registerAll(Collection<Tool<?>> tools);

    /**
     * Get a tool by name.
     */
    Optional<Tool<?>> getTool(String name);

    /**
     * Get all tool names.
     */
    Set<String> getToolNames();

    /**
     * Get all registered tools.
     */
    Collection<Tool<?>> getAllTools();

    /**
     * Check if a tool exists.
     */
    boolean hasTool(String name);

    /**
     * Get tool schemas for LLM function calling.
     * 
     * @param includeTools list of tool names to include (null = all)
     * @return list of tool schemas
     */
    List<ToolSchema> getToolSchemas(List<String> includeTools);

    /**
     * Unregister a tool.
     */
    void unregister(String toolName);

    /**
     * Clear all tools.
     */
    void clear();
}
