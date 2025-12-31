package io.leavesfly.jimi.config.info;

import lombok.Data;

/**
 * 记忆模块配置
 * 基于 ReCAP 理念的记忆优化配置 + 长期记忆管理
 *
 * @see <a href="https://github.com/ReCAP-Stanford/ReCAP">ReCAP: Recursive Context-Aware Reasoning and Planning</a>
 */
@Data
public class MemoryConfig {


    // ==================== 长期记忆相关配置 ====================

    /**
     * 是否启用长期记忆
     * 默认关闭，通过配置开关启用
     */
    private boolean longTermEnabled = false;

    /**
     * 是否自动从工具结果提取知识
     * 默认开启（启用长期记忆时生效）
     */
    private boolean autoExtract = true;

    /**
     * 是否自动注入相关知识到上下文
     * 默认开启（启用长期记忆时生效）
     */
    private boolean autoInject = true;

    /**
     * 最多保留的知识条目数量
     * 默认 100 条，超出后按访问频率清理
     */
    private int maxInsights = 100;

    /**
     * 知识过期天数
     * 默认 90 天，超过此期限未访问的知识将被清理
     */
    private int insightExpiryDays = 90;

    /**
     * 最多保留的任务历史数量
     * 默认 50 条，超出后按时间清理
     */
    private int maxTaskHistory = 50;
}
