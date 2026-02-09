package io.leavesfly.jimi.adk.knowledge.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一的记忆条目数据模型
 * 用于存储所有类型的长期记忆
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEntry {
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("type")
    private MemoryType type;
    
    @JsonProperty("content")
    private String content;
    
    @JsonProperty("metadata")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    @JsonProperty("createdAt")
    private Instant createdAt;
    
    @JsonProperty("updatedAt")
    private Instant updatedAt;
    
    @JsonProperty("accessCount")
    @Builder.Default
    private int accessCount = 0;
    
    @JsonProperty("lastAccessed")
    private Instant lastAccessed;
    
    @JsonProperty("confidence")
    @Builder.Default
    private double confidence = 0.8;
    
    public void incrementAccess() {
        this.accessCount++;
        this.lastAccessed = Instant.now();
    }
    
    public void touch() {
        this.updatedAt = Instant.now();
    }
    
    public Object getMetadata(String key) {
        return metadata.get(key);
    }
    
    public void setMetadata(String key, Object value) {
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put(key, value);
    }
    
    public String getMetadataString(String key) {
        Object value = metadata.get(key);
        return value != null ? value.toString() : null;
    }
    
    public Integer getMetadataInt(String key) {
        Object value = metadata.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }
    
    public Boolean getMetadataBoolean(String key) {
        Object value = metadata.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return null;
    }
}
