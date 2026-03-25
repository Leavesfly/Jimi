package io.leavesfly.jimi.tool.skill;

import lombok.Data;

/**
 * Skills 功能配置类
 * 
 * 配置 Skills 的行为
 */
@Data
public class SkillConfig {
    
    /**
     * 是否启用 Skills 功能
     * 默认：true
     */
    private boolean enabled = true;
    
    /**
     * 是否自动匹配 Skills
     * 如果为 false，则不会自动匹配，需要手动激活
     * 默认：true
     */
    private boolean autoMatch = true;
    
    /**
     * 是否启用 Claude Code Skill 兼容模式
     * 开启后，对于没有 triggers 字段的 Skill，会自动从 name 和 description 生成 triggers
     * 默认：true
     */
    private boolean enableClaudeCodeCompatibility = true;
    
    /**
     * 匹配相关配置
     */
    private MatchingConfig matching = new MatchingConfig();
    
    /**
     * 缓存相关配置
     */
    private CacheConfig cache = new CacheConfig();
    
    /**
     * 日志相关配置
     */
    private LoggingConfig logging = new LoggingConfig();
    

    /**
     * 匹配配置
     */
    @Data
    public static class MatchingConfig {
        /**
         * 用户输入匹配的最低得分阈值（0-100）
         * 低于此分数的 Skill 不会被激活
         * 默认：30
         */
        private int scoreThreshold = 30;
        
        /**
         * 上下文匹配的最低得分阈值（0-100）
         * 通常设置为比用户输入匹配更低的值
         * 默认：15
         */
        private int contextScoreThreshold = 15;
        
        /**
         * 最大匹配 Skills 数量
         * 限制同时激活的 Skills 数量，避免上下文过长
         * 默认：5
         */
        private int maxMatchedSkills = 5;
        
        /**
         * 是否启用上下文动态匹配
         * 如果启用，会在每个步骤中根据对话上下文动态匹配 Skills
         * 默认：false（暂未实现）
         */
        private boolean enableContextMatching = false;
    }
    
    /**
     * 缓存配置
     */
    @Data
    public static class CacheConfig {
        /**
         * 是否启用缓存
         * 缓存匹配结果以提升性能
         * 默认：true
         */
        private boolean enabled = true;
        
        /**
         * 缓存过期时间（秒）
         * 默认：3600（1小时）
         */
        private long ttl = 3600;
        
        /**
         * 缓存最大条目数
         * 默认：1000
         */
        private int maxSize = 1000;
    }
    
    /**
     * 日志配置
     */
    @Data
    public static class LoggingConfig {
        /**
         * 是否记录匹配详情
         * 包括关键词提取、得分计算等
         * 默认：false
         */
        private boolean logMatchDetails = false;
        
        /**
         * 是否记录注入详情
         * 包括格式化内容、激活状态等
         * 默认：false
         */
        private boolean logInjectionDetails = false;
        
        /**
         * 是否记录性能指标
         * 包括匹配耗时、注入耗时等
         * 默认：false
         */
        private boolean logPerformanceMetrics = false;
    }
    

    /**
     * 验证配置有效性
     * 
     * @throws IllegalArgumentException 如果配置无效
     */
    public void validate() {
        if (matching.scoreThreshold < 0 || matching.scoreThreshold > 100) {
            throw new IllegalArgumentException(
                    "scoreThreshold must be between 0 and 100, got: " + matching.scoreThreshold);
        }
        
        if (matching.contextScoreThreshold < 0 || matching.contextScoreThreshold > 100) {
            throw new IllegalArgumentException(
                    "contextScoreThreshold must be between 0 and 100, got: " + matching.contextScoreThreshold);
        }
        
        if (matching.maxMatchedSkills < 1) {
            throw new IllegalArgumentException(
                    "maxMatchedSkills must be at least 1, got: " + matching.maxMatchedSkills);
        }
        
        if (cache.ttl < 0) {
            throw new IllegalArgumentException(
                    "cache TTL must be non-negative, got: " + cache.ttl);
        }
        
        if (cache.maxSize < 1) {
            throw new IllegalArgumentException(
                    "cache maxSize must be at least 1, got: " + cache.maxSize);
        }
        

    }
}
