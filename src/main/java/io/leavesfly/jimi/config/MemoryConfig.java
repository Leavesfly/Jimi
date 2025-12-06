package io.leavesfly.jimi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 记忆模块配置
 * 基于 ReCAP 理念的记忆优化配置
 * 
 * @see <a href="https://github.com/ReCAP-Stanford/ReCAP">ReCAP: Recursive Context-Aware Reasoning and Planning</a>
 */
@Data
@Component
@ConfigurationProperties(prefix = "jimi.memory")
public class MemoryConfig {
    
    /**
     * 有界提示最大 Token 数
     * 默认 4000，确保提示大小保持 O(1)
     */
    private int activePromptMaxTokens = 4000;
    
    /**
     * 关键发现窗口大小
     * 默认保留最近 5 条关键发现进入活动提示
     */
    private int insightsWindowSize = 5;
    
    /**
     * 是否启用 ReCAP 优化
     * 默认关闭，通过配置开关逐步启用
     */
    private boolean enableRecap = false;
    
    /**
     * 最大递归深度
     * 默认 5 层，防止无限递归
     */
    private int maxRecursionDepth = 5;
}
