package io.leavesfly.jimi.config.info;

import lombok.Data;

/**
 * 记忆系统配置
 */
@Data
public class MemoryConfig {
    /**
     * 是否启用记忆系统
     */
    private boolean enabled = true;

    /**
     * 记忆存储根目录（默认 ~/.jimi/memory）
     */
    private String storagePath = "";

    /**
     * MEMORY.md 最大 Token 数限制
     */
    private int maxMemoryTokens = 2000;

    /**
     * 是否自动提取记忆（Phase 2 使用）
     */
    private boolean autoExtract = true;

    /**
     * 是否自动整理记忆（Phase 5 使用）
     */
    private boolean autoConsolidate = true;

    /**
     * 自动整理触发条件：最少会话数
     */
    private int consolidateMinSessions = 5;

    /**
     * 自动整理触发条件：最少间隔小时数
     */
    private int consolidateMinHours = 24;
}
