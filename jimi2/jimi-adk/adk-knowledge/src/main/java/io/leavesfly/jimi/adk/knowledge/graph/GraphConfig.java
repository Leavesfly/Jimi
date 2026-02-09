package io.leavesfly.jimi.adk.knowledge.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * 代码图配置
 * <p>
 * 支持代码图功能的启用/禁用、自动构建、缓存和搜索配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphConfig {
    
    /**
     * 是否启用代码图功能
     * 默认：true
     */
    @Builder.Default
    private Boolean enabled = true;
    
    /**
     * 是否自动构建代码图
     * 当文件变化时自动重新构建
     * 默认：false (需手动触发)
     */
    @Builder.Default
    private Boolean autoBuild = false;
    
    /**
     * 启动时是否构建代码图
     * 默认：false (延迟构建)
     */
    @Builder.Default
    private Boolean buildOnStartup = false;
    
    /**
     * 图存储路径
     * 默认：.jimi/code_graph
     */
    @Builder.Default
    private String storagePath = ".jimi/code_graph";
    
    /**
     * 启动时是否自动加载已保存的图
     * 默认：true
     */
    @Builder.Default
    private Boolean autoLoad = true;
    
    /**
     * 构建后是否自动保存
     * 默认：true
     */
    @Builder.Default
    private Boolean autoSave = true;
    
    /**
     * 包含文件模式
     * 默认：仅 Java 文件
     */
    @Builder.Default
    private Set<String> includePatterns = new HashSet<>(Set.of("**/*.java"));
    
    /**
     * 排除文件模式
     * 默认：排除测试和构建目录
     */
    @Builder.Default
    private Set<String> excludePatterns = new HashSet<>(Set.of(
        "**/test/**",
        "**/tests/**",
        "**/target/**",
        "**/build/**",
        "**/node_modules/**",
        "**/.git/**"
    ));
    
    /**
     * 缓存配置
     */
    @Builder.Default
    private CacheConfig cache = new CacheConfig();
    
    /**
     * 搜索配置
     */
    @Builder.Default
    private SearchConfig search = new SearchConfig();
    
    /**
     * 缓存配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheConfig {
        
        /**
         * 是否启用缓存
         */
        @Builder.Default
        private Boolean enabled = true;
        
        /**
         * 缓存过期时间（秒）
         * -1 表示永不过期
         */
        @Builder.Default
        private Integer ttl = 3600;
        
        /**
         * 最大缓存条目数
         */
        @Builder.Default
        private Integer maxSize = 10000;
    }
    
    /**
     * 搜索配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchConfig {
        
        /**
         * 最大搜索结果数
         */
        @Builder.Default
        private Integer maxResults = 50;
        
        /**
         * 是否启用混合检索（图+向量）
         */
        @Builder.Default
        private Boolean enableHybrid = true;
        
        /**
         * 图检索权重（0.0-1.0）
         */
        @Builder.Default
        private Double graphWeight = 0.6;
        
        /**
         * 向量检索权重（0.0-1.0）
         */
        @Builder.Default
        private Double vectorWeight = 0.4;
        
        /**
         * 最小相似度阈值
         */
        @Builder.Default
        private Double minSimilarity = 0.3;
    }
}
