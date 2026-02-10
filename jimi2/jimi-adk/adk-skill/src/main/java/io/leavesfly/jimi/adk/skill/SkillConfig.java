package io.leavesfly.jimi.adk.skill;

import lombok.Data;

/**
 * Skills 功能配置类
 */
@Data
public class SkillConfig {
    
    private boolean enabled = true;
    private boolean autoMatch = true;
    private boolean enableClaudeCodeCompatibility = true;
    private MatchingConfig matching = new MatchingConfig();
    private CacheConfig cache = new CacheConfig();
    private LoggingConfig logging = new LoggingConfig();
    private ScriptExecutionConfig scriptExecution = new ScriptExecutionConfig();
    
    @Data
    public static class MatchingConfig {
        /** 用户输入匹配的最低得分阈值（0-100） */
        private int scoreThreshold = 30;
        /** 上下文匹配的最低得分阈值 */
        private int contextScoreThreshold = 15;
        /** 最大匹配 Skills 数量 */
        private int maxMatchedSkills = 5;
        /** 是否启用上下文动态匹配 */
        private boolean enableContextMatching = false;
    }
    
    @Data
    public static class CacheConfig {
        private boolean enabled = true;
        /** 缓存过期时间（秒） */
        private long ttl = 3600;
        private int maxSize = 1000;
    }
    
    @Data
    public static class LoggingConfig {
        private boolean logMatchDetails = false;
        private boolean logInjectionDetails = false;
        private boolean logPerformanceMetrics = false;
    }
    
    @Data
    public static class ScriptExecutionConfig {
        private boolean enabled = true;
        /** 脚本执行超时时间（秒） */
        private int timeout = 60;
        private boolean requireApproval = false;
    }
    
    public void validate() {
        if (matching.scoreThreshold < 0 || matching.scoreThreshold > 100) {
            throw new IllegalArgumentException("scoreThreshold must be between 0 and 100");
        }
        if (matching.maxMatchedSkills < 1) {
            throw new IllegalArgumentException("maxMatchedSkills must be at least 1");
        }
        if (cache.ttl < 0) {
            throw new IllegalArgumentException("cache TTL must be non-negative");
        }
        if (scriptExecution.timeout < 1) {
            throw new IllegalArgumentException("script timeout must be at least 1");
        }
    }
}
