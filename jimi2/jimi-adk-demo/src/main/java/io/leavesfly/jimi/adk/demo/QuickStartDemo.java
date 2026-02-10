package io.leavesfly.jimi.adk.demo;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.core.JimiRuntime;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 快速入门示例 - 最简单的 Agent 应用
 * <p>
 * 演示如何使用 ADK 创建并运行一个最基础的 AI Agent。
 * </p>
 *
 * <p>运行方式：
 * <pre>
 * mvn exec:java -Dexec.mainClass="io.leavesfly.jimi.adk.demo.QuickStartDemo"
 * </pre>
 * </p>
 */
@Slf4j
public class QuickStartDemo {

    public static void main(String[] args) {
        log.info("=== Jimi ADK 快速入门示例 ===");

        // Step 1: 创建 Agent（最小化配置）
        Agent agent = Agent.builder()
                .name("hello-agent")
                .description("一个简单的问候 Agent")
                .systemPrompt("你是一个友好的 AI 助手，擅长简洁地回答问题。")
                .build();

        log.info("✓ Agent 创建完成: {}", agent.getName());

        // Step 2: 创建 LLM（需要配置 API Key）
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("✗ 请设置环境变量 OPENAI_API_KEY");
            System.exit(1);
        }

        LLMConfig llmConfig = LLMConfig.builder()
                .provider("openai")
                .model("gpt-4o-mini")
                .apiKey(apiKey)
                .build();

        LLMFactory llmFactory = new LLMFactory();
        LLM llm = llmFactory.create(llmConfig);

        log.info("✓ LLM 创建完成: {}/{}", llm.getProvider(), llm.getModel());

        // Step 3: 构建运行时（使用 JimiRuntime 统一组装）
        Path workDir = Paths.get(System.getProperty("user.dir"));
        JimiRuntime runtime = JimiRuntime.builder()
                .agent(agent)
                .llm(llm)
                .workDir(workDir)
                .build();

        log.info("✓ Runtime 初始化完成，工作目录: {}", workDir);

        // Step 4: 执行对话
        String userMessage = "你好，请介绍一下你自己";
        log.info("\n>>> 用户: {}", userMessage);

        runtime.getEngine().run(userMessage)
                .doOnNext(result -> log.info("<<< Agent: {}", result.getResponse()))
                .doOnError(e -> log.error("✗ 异常: {}", e.getMessage(), e))
                .block();

        log.info("\n=== 示例执行完成 ===");
    }
}
