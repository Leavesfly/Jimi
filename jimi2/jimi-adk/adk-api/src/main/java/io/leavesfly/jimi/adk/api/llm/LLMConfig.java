package io.leavesfly.jimi.adk.api.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMConfig {
    
    /**
     * 提供商类型（openai, kimi, deepseek, qwen, ollama 等）
     */
    private String provider;
    
    /**
     * 模型名称
     */
    private String model;
    
    /**
     * API Key
     */
    private String apiKey;
    
    /**
     * API 基础 URL
     */
    private String baseUrl;
    
    /**
     * 温度参数（0-2）
     */
    @Builder.Default
    private double temperature = 0.7;
    
    /**
     * 最大输出 Token 数
     */
    @Builder.Default
    private int maxTokens = 4096;
    
    /**
     * 连接超时（秒）
     */
    @Builder.Default
    private int connectTimeout = 30;
    
    /**
     * 读取超时（秒）
     */
    @Builder.Default
    private int readTimeout = 120;
}
