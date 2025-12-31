package io.leavesfly.jimi.knowledge.domain.result;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 长期记忆查询结果
 * 
 * <p>封装Memory模块的查询结果，包括：
 * - 记忆条目列表
 * - 操作结果
 */
@Data
@Builder
public class MemoryResult {
    
    /**
     * 操作是否成功
     */
    @Builder.Default
    private boolean success = true;
    
    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;
    
    /**
     * 记忆条目列表
     */
    @Builder.Default
    private List<MemoryEntry> entries = Collections.emptyList();
    
    /**
     * 受影响的记忆ID（用于ADD/UPDATE/DELETE操作）
     */
    private String affectedId;
    
    /**
     * 总记忆数量
     */
    private int totalCount;
    
    /**
     * 创建成功结果
     */
    public static MemoryResult success(List<MemoryEntry> entries) {
        return MemoryResult.builder()
                .success(true)
                .entries(entries)
                .totalCount(entries.size())
                .build();
    }
    
    /**
     * 创建操作成功结果
     */
    public static MemoryResult operationSuccess(String affectedId) {
        return MemoryResult.builder()
                .success(true)
                .affectedId(affectedId)
                .build();
    }
    
    /**
     * 创建错误结果
     */
    public static MemoryResult error(String message) {
        return MemoryResult.builder()
                .success(false)
                .errorMessage(message)
                .build();
    }
    
    /**
     * 记忆条目
     */
    @Data
    @Builder
    public static class MemoryEntry {
        /** 记忆ID */
        private String id;
        
        /** 记忆类型 */
        private String type;
        
        /** 记忆内容 */
        private String content;
        
        /** 创建时间 */
        private Instant createdAt;
        
        /** 更新时间 */
        private Instant updatedAt;
        
        /** 访问次数 */
        private int accessCount;
        
        /** 相关度分数（检索时） */
        private double relevanceScore;
        
        /** 关联的代码实体ID（可选） */
        private String relatedEntityId;
    }
}
