package io.leavesfly.jimi.adk.demo;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.core.JimiRuntime;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Paths;
import java.util.List;

/**
 * 多种LLM提供商演示示例
 * <p>
 * 演示如何切换不同的 LLM 提供商（OpenAI、DeepSeek、Kimi、Qwen）。
 * </p>
 */
@Slf4j
public class LLMProviderDemo {

    public static void main(String[] args) {
        log.info("=== 多种LLM提供商演示示例 ===");

        // 测试不同提供商
        List<ProviderConfig> providers = List.of(
                new ProviderConfig("openai", "gpt-4o-mini", "OpenAI"),
                new ProviderConfig("deepseek", "deepseek-chat", "DeepSeek"),
                new ProviderConfig("kimi", "moonshot-v1-8k", "Kimi"),
                new ProviderConfig("qwen", "qwen-plus", "通义千问")
        );

        String userMessage = "请用一句话介绍你自己";

        for (ProviderConfig config : providers) {
            log.info("\n--- 测试 {} ---", config.displayName);
            
            try {
                testProvider(config, userMessage);
            } catch (Exception e) {
                log.error("✗ {} 测试失败: {}", config.displayName, e.getMessage());
            }
        }

        log.info("\n=== 示例完成 ===");
    }

    /**
     * 测试指定提供商
     */
    private static void testProvider(ProviderConfig config, String userMessage) {
        // 获取对应环境变量
        String apiKey = System.getenv(config.provider.toUpperCase() + "_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("✗ 请设置环境变量 {}_API_KEY", config.provider.toUpperCase());
            return;
        }

        // 创建 LLM
        LLMConfig llmConfig = LLMConfig.builder()
                .provider(config.provider)
                .model(config.model)
                .apiKey(apiKey)
                .temperature(0.7f)
                .build();

        LLMFactory llmFactory = new LLMFactory();
        LLM llm = llmFactory.create(llmConfig);

        log.info("✓ LLM 创建成功: {}/{}", llm.getProvider(), llm.getModel());

        // 创建 Agent
        Agent agent = Agent.builder()
                .name(config.provider + "-agent")
                .description(config.displayName + " 测试 Agent")
                .systemPrompt("你是一个友好的 AI 助手，请简洁地回答问题。")
                .build();

        // 构建运行时
        JimiRuntime runtime = JimiRuntime.builder()
                .agent(agent)
                .llm(llm)
                .workDir(Paths.get(System.getProperty("user.dir"))
                        .resolve(".llm-demo-" + config.provider))
                .build();

        // 执行对话
        log.info(">>> 用户: {}", userMessage);
        
        runtime.getEngine().run(userMessage)
                .doOnNext(result -> log.info("<<< Agent: {}", result.getResponse()))
                .doOnError(e -> log.error("✗ 错误: {}", e.getMessage()))
                .block();
    }

    /**
     * 提供商配置
     */
    private static class ProviderConfig {
        public final String provider;
        public final String model;
        public final String displayName;

        public ProviderConfig(String provider, String model, String displayName) {
            this.provider = provider;
            this.model = model;
            this.displayName = displayName;
        }
    }
}
