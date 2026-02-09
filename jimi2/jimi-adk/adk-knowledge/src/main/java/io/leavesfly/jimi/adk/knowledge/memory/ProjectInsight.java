package io.leavesfly.jimi.adk.knowledge.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 项目知识条目
 * 从工具执行结果中提取的关键发现
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectInsight {
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("category")
    private String category;
    
    @JsonProperty("timestamp")
    private Instant timestamp;
    
    @JsonProperty("content")
    private String content;
    
    @JsonProperty("source")
    private String source;
    
    @JsonProperty("confidence")
    @Builder.Default
    private double confidence = 0.8;
    
    @JsonProperty("accessCount")
    @Builder.Default
    private int accessCount = 0;
    
    @JsonProperty("lastAccessed")
    private Instant lastAccessed;
    
    public void incrementAccess() {
        this.accessCount++;
        this.lastAccessed = Instant.now();
    }
}
