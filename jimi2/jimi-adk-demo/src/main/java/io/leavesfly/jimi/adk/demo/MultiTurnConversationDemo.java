package io.leavesfly.jimi.adk.demo;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.core.JimiRuntime;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Paths;
import java.util.List;

/**
 * 多轮对话示例
 * <p>
 * 演示如何利用 Context 进行多轮对话，Agent 能够记住上下文。
 * </p>
 */
@Slf4j
public class MultiTurnConversationDemo {

    public static void main(String[] args) {
        log.info("=== 多轮对话示例 ===");

        // 创建 Agent
        Agent agent = Agent.builder()
                .name("chat-agent")
                .description("支持多轮对话的 Agent")
                .systemPrompt("你是一个友好的助手，记得之前的对话内容。")
                .build();

        // 创建 LLM
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

        // 构建运行时
        JimiRuntime runtime = JimiRuntime.builder()
                .agent(agent)
                .llm(llm)
                .workDir(Paths.get(System.getProperty("user.dir")))
                .build();

        Context context = runtime.getContext();

        // 模拟多轮对话
        String[] conversations = {
                "我的名字是张三",
                "我今年 25 岁",
                "请问你还记得我的名字吗？",
                "我多大了？"
        };

        for (String userMessage : conversations) {
            log.info("\n>>> 用户: {}", userMessage);

            runtime.getEngine().run(userMessage)
                    .doOnNext(result -> log.info("<<< Agent: {}", result.getResponse()))
                    .block();

            // 打印当前对话历史摘要
            List<Message> history = context.getHistory();
            log.info("    [历史消息数: {}]", history.size());
        }

        log.info("\n=== 完整对话历史 ===");
        List<Message> allMessages = context.getHistory();
        allMessages.forEach(msg -> 
                log.info("{}: {}", msg.getRole(), msg.getContent())
        );

        log.info("\n=== 示例完成 ===");
    }
}
