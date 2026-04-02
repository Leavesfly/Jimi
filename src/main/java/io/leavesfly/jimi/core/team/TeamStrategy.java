package io.leavesfly.jimi.core.team;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 团队调度策略
 */
public enum TeamStrategy {

    /**
     * 自由认领：Teammate 自主从任务列表中认领任务（默认）
     */
    FREE_CLAIM("free_claim"),

    /**
     * 轮询分配：按顺序轮流分配任务给 Teammate
     */
    ROUND_ROBIN("round_robin"),

    /**
     * 按擅长领域匹配：根据 Teammate 的 specialties 智能分配
     */
    SPECIALTY_MATCH("specialty_match");

    private final String value;

    TeamStrategy(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 从字符串解析策略
     */
    public static TeamStrategy fromString(String value) {
        if (value == null || value.isBlank()) {
            return FREE_CLAIM;
        }
        String normalized = value.trim().toLowerCase().replace("-", "_");
        for (TeamStrategy strategy : values()) {
            if (strategy.value.equals(normalized) || strategy.name().equalsIgnoreCase(normalized)) {
                return strategy;
            }
        }
        return FREE_CLAIM;
    }
}
