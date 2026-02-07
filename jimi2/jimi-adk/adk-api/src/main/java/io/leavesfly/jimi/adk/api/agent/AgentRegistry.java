package io.leavesfly.jimi.adk.api.agent;

import io.leavesfly.jimi.adk.api.engine.Runtime;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Agent registry interface.
 * Manages agent specifications and instances.
 */
public interface AgentRegistry {
    
    /**
     * Load an agent specification from a file.
     *
     * @param agentFile path to agent configuration file
     * @return agent specification
     */
    Mono<AgentSpec> loadAgentSpec(Path agentFile);
    
    /**
     * Load an agent instance.
     *
     * @param agentFile path to agent configuration file
     * @param runtime runtime context
     * @return agent instance
     */
    Mono<Agent> loadAgent(Path agentFile, Runtime runtime);
    
    /**
     * Load the default agent.
     *
     * @param runtime runtime context
     * @return default agent instance
     */
    Mono<Agent> loadDefaultAgent(Runtime runtime);
    
    /**
     * Load a subagent.
     *
     * @param subagentSpec subagent specification
     * @param runtime runtime context
     * @return subagent instance
     */
    Mono<Agent> loadSubagent(SubagentSpec subagentSpec, Runtime runtime);
    
    /**
     * List all available agent names.
     *
     * @return list of agent names
     */
    List<String> listAvailableAgents();
    
    /**
     * Get all cached agent specifications.
     *
     * @return map of path to agent spec
     */
    Map<Path, AgentSpec> getAllAgentSpecs();
    
    /**
     * Get the default agent path.
     *
     * @return default agent configuration path
     */
    Path getDefaultAgentPath();
}
