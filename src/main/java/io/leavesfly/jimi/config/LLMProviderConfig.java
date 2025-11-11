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
     * 提供商类型枚举
     */
    public enum ProviderType {
        @JsonProperty("kimi") KIMI,

        @JsonProperty("deepseek") DEEPSEEK,

        @JsonProperty("qwen") QWEN,

        @JsonProperty("ollama") OLLAMA,

        @JsonProperty("openai") OPENAI,

        @JsonProperty("claude") CLAUDE

    }
}
