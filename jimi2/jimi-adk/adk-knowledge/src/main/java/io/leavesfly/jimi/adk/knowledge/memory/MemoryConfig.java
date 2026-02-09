package io.leavesfly.jimi.adk.knowledge.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记忆模块配置
 * <p>
 * 控制长期记忆的启用/提取/注入/清理策略。
 * 后续会被 KnowledgeConfig 统一引用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryConfig {

    /**
     * 是否启用长期记忆
     */
    @Builder.Default
    private boolean longTermEnabled = false;

    /**
     * 是否自动从工具结果提取知识
     */
    @Builder.Default
    private boolean autoExtract = true;

    /**
     * 是否自动注入相关知识到上下文
     */
    @Builder.Default
    private boolean autoInject = true;

    /**
     * 最多保留的知识条目数量
     */
    @Builder.Default
    private int maxInsights = 100;

    /**
     * 知识过期天数
     */
    @Builder.Default
    private int insightExpiryDays = 90;

    /**
     * 最多保留的任务历史数量
     */
    @Builder.Default
    private int maxTaskHistory = 50;
}
