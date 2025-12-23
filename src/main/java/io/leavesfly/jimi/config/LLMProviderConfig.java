package io.leavesfly.jimi.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * LLM 提供商配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMProviderConfig {

    /**
     * 提供商类型
     */
    @JsonProperty("type")
    private ProviderType type;

    /**
     * API 基础 URL
     */
    @JsonProperty("base_url")
    private String baseUrl;

    /**
     * API 密钥
     */
    @JsonProperty("api_key")
    private String apiKey;

    /**
     * 自定义请求头
     */
    @JsonProperty("custom_headers")
    @Builder.Default
    private Map<String, String> customHeaders = new HashMap<>();

    /**
     * 限流配置
     */
    @JsonProperty("rate_limit")
    private RateLimitConfig rateLimit;

    /**
     * 限流配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimitConfig {
        /**
         * 时间窗口（毫秒）
         */
        @JsonProperty("window_ms")
        private long windowMs;

        /**
         * 时间窗口内最大请求数
         */
        @JsonProperty("max_requests")
        private int maxRequests;

        /**
         * 超过限流时的等待时间（毫秒）
         */
        @JsonProperty("sleep_ms")
        private long sleepMs;
    }

    /**
     * 提供商类型枚举
     */
    public enum ProviderType {
        @JsonProperty("kimi") KIMI,

        @JsonProperty("deepseek") DEEPSEEK,

        @JsonProperty("qwen") QWEN,

        @JsonProperty("ollama") OLLAMA,

        @JsonProperty("openai") OPENAI,

        @JsonProperty("claude") CLAUDE,

        @JsonProperty("cursor") CURSOR,

        @JsonProperty("glm") GLM

    }
}
