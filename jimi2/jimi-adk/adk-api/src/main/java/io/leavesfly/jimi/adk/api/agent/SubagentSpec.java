package io.leavesfly.jimi.adk.api.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * Subagent specification - defines a subagent that can be delegated to.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubagentSpec {
    
    /**
     * Subagent type/name (used as identifier)
     */
    private String type;
    
    /**
     * Human-readable description
     */
    private String description;
    
    /**
     * Path to the subagent configuration file
     */
    private Path path;
    
    /**
     * Whether this subagent runs asynchronously
     */
    @Builder.Default
    private boolean async = false;
}
