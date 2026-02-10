package io.leavesfly.jimi.adk.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 强类型配置属性
 * <p>
 * 从 YAML 配置文件的 {@code llm} 节点反序列化。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProperties {

    /** 提供商类型（openai, kimi, deepseek, qwen, ollama 等） */
    @Builder.Default
    private String provider = "openai";

    /** 模型名称 */
    @Builder.Default
    private String model = "gpt-4o";

    /** API Key（支持 ${ENV_VAR} 语法） */
    private String apiKey;

    /** API 基础 URL */
    private String baseUrl;

    /** 温度参数（0-2） */
    @Builder.Default
    private double temperature = 0.7;

    /** 最大输出 Token 数 */
    @Builder.Default
    private int maxTokens = 4096;

    /** 连接超时（秒） */
    @Builder.Default
    private int connectTimeout = 30;

    /** 读取超时（秒） */
    @Builder.Default
    private int readTimeout = 120;
}
