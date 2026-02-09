package io.leavesfly.jimi.adk.knowledge.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 任务模式
 * 记录常见任务的执行步骤和经验
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskPattern {
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("trigger")
    private String trigger;
    
    @JsonProperty("steps")
    private List<String> steps;
    
    @JsonProperty("usageCount")
    @Builder.Default
    private int usageCount = 0;
    
    @JsonProperty("successRate")
    @Builder.Default
    private double successRate = 1.0;
    
    @JsonProperty("lastUsed")
    private Instant lastUsed;
    
    public void incrementUsage() {
        this.usageCount++;
        this.lastUsed = Instant.now();
    }
}
