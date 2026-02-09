package io.leavesfly.jimi.adk.api.knowledge.query;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 长期记忆查询请求
 * 
 * <p>用于Memory模块的记忆管理，支持：
 * - 查询/添加/删除/更新记忆
 * - 语义检索和关键词检索
 * - 按类型和时间范围过滤
 */
@Data
@Builder
public class MemoryQuery {
    
    /**
     * 操作类型
     */
    private OperationType operation;
    
    /**
     * 记忆类型
     */
    private MemoryType type;
    
    /**
     * 查询文本（用于语义检索或关键词检索）
     */
    private String query;
    
    /**
     * 记忆内容（用于ADD/UPDATE操作）
     */
    private String content;
    
    /**
     * 记忆ID（用于UPDATE/DELETE操作）
     */
    private String memoryId;
    
    /**
     * 最大返回结果数
     */
    @Builder.Default
    private int limit = 5;
    
    /**
     * 时间范围过滤
     */
    private TimeRange timeRange;
    
    /**
     * 操作类型枚举
     */
    public enum OperationType {
        /** 查询记忆 */
        QUERY,
        
        /** 添加记忆 */
        ADD,
        
        /** 删除记忆 */
        DELETE,
        
        /** 更新记忆 */
        UPDATE
    }
    
    /**
     * 记忆类型枚举
     */
    public enum MemoryType {
        /** 用户偏好 */
        USER_PREFERENCE,
        
        /** 项目洞察 */
        PROJECT_INSIGHT,
        
        /** 错误模式 */
        ERROR_PATTERN,
        
        /** 任务历史 */
        TASK_HISTORY,
        
        /** 会话摘要 */
        SESSION_SUMMARY,
        
        /** 所有类型 */
        ALL
    }
    
    /**
     * 时间范围
     */
    @Data
    @Builder
    public static class TimeRange {
        private Instant from;
        private Instant to;
    }
}
