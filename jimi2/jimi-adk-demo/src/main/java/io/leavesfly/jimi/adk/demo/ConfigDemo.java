package io.leavesfly.jimi.adk.demo;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.context.PersistableContext;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.core.JimiRuntime;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 配置管理演示示例
 * <p>
 * 演示如何通过配置文件管理 Agent 和 LLM 的配置。
 * </p>
 */
@Slf4j
public class ConfigDemo {

    public static void main(String[] args) {
        log.info("=== 配置管理演示示例 ===");

        // 1. 从配置文件加载 Agent 配置
        AgentConfig agentConfig = loadAgentConfig();
        log.info("✓ 加载 Agent 配置: name={}, model={}", 
                agentConfig.name, agentConfig.llmModel);

        // 2. 创建 Agent
        Agent agent = Agent.builder()
                .name(agentConfig.name)
                .description(agentConfig.description)
                .systemPrompt(agentConfig.systemPrompt)
                .build();

        // 3. 创建 LLM
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("✗ 请设置环境变量 OPENAI_API_KEY");
            System.exit(1);
        }

        LLMConfig llmConfig = LLMConfig.builder()
                .provider(agentConfig.llmProvider)
                .model(agentConfig.llmModel)
                .apiKey(apiKey)
                .temperature(agentConfig.temperature)
                .maxTokens(agentConfig.maxTokens)
                .build();

        LLMFactory llmFactory = new LLMFactory();
        LLM llm = llmFactory.create(llmConfig);

        // 4. 构建运行时
        Path workDir = Paths.get(System.getProperty("user.dir")).resolve(".demo-session");
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            log.warn("创建工作目录失败: {}", e.getMessage());
        }

        JimiRuntime runtime = JimiRuntime.builder()
                .agent(agent)
                .llm(llm)
                .workDir(workDir)
                .build();

        // 5. 执行对话
        String[] messages = {
                "你好，我是谁？",
                "我的配置是什么样的？",
                "你能告诉我你的系统提示词吗？"
        };

        for (String msg : messages) {
            log.info("\n>>> 用户: {}", msg);
            
            runtime.getEngine().run(msg)
                    .doOnNext(result -> log.info("<<< Agent: {}", result.getResponse()))
                    .block();
        }

        log.info("\n=== 示例完成 ===");
    }

    /**
     * 加载 Agent 配置（模拟从配置文件读取）
     */
    private static AgentConfig loadAgentConfig() {
        // 实际项目中可以从 YAML/JSON 文件加载
        return new AgentConfig(
                "config-demo-agent",
                "配置演示 Agent",
                """
                你是一个配置演示助手。
                你的配置如下：
                - 名称: config-demo-agent
                - 模型: gpt-4o-mini
                - 温度: 0.7
                - 最大Token: 2000
                """,
                "openai",
                "gpt-4o-mini",
                0.7f,
                2000
        );
    }

    /**
     * Agent 配置类
     */
    private static class AgentConfig {
        public final String name;
        public final String description;
        public final String systemPrompt;
        public final String llmProvider;
        public final String llmModel;
        public final float temperature;
        public final int maxTokens;

        public AgentConfig(String name, String description, String systemPrompt,
                          String llmProvider, String llmModel, 
                          float temperature, int maxTokens) {
            this.name = name;
            this.description = description;
            this.systemPrompt = systemPrompt;
            this.llmProvider = llmProvider;
            this.llmModel = llmModel;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
        }
    }
}
