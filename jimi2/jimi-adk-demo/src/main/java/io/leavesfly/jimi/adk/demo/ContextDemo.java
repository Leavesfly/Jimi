package io.leavesfly.jimi.adk.demo;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.context.PersistableContext;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.core.JimiRuntime;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 上下文持久化演示示例
 * <p>
 * 演示如何保存和恢复对话上下文，实现会话延续。
 * </p>
 */
@Slf4j
public class ContextDemo {

    private static final Path CONTEXT_FILE = Paths.get(".context-demo-state.bin");

    public static void main(String[] args) {
        log.info("=== 上下文持久化演示示例 ===");

        // 第一次对话
        log.info("\n--- 第一次对话 ---");
        JimiRuntime runtime1 = createRuntime();
        conductConversation(runtime1, new String[]{
                "你好，我的名字叫李明",
                "我喜欢编程和阅读"
        });

        // 保存上下文
        Context context1 = runtime1.getContext();
        if (context1 instanceof PersistableContext persistable) {
            try {
                boolean saved = persistable.save(CONTEXT_FILE);
                log.info("✓ 上下文保存 {}: {}", 
                        saved ? "成功" : "失败", CONTEXT_FILE.toAbsolutePath());
            } catch (Exception e) {
                log.error("✗ 保存上下文失败: {}", e.getMessage());
            }
        }

        // 第二次对话（新运行时）
        log.info("\n--- 第二次对话（恢复上下文） ---");
        JimiRuntime runtime2 = createRuntime();
        
        // 恢复上下文
        Context context2 = runtime2.getContext();
        if (context2 instanceof PersistableContext persistable) {
            try {
                boolean restored = persistable.restore(CONTEXT_FILE);
                log.info("✓ 上下文恢复 {}: {}", 
                        restored ? "成功" : "失败", CONTEXT_FILE.toAbsolutePath());
                
                if (restored) {
                    log.info("  恢复后历史消息数: {}", context2.getHistory().size());
                }
            } catch (Exception e) {
                log.error("✗ 恢复上下文失败: {}", e.getMessage());
            }
        }

        // 继续对话
        conductConversation(runtime2, new String[]{
                "还记得我的名字吗？",
                "我喜欢做什么？"
        });

        log.info("\n=== 示例完成 ===");
    }

    /**
     * 创建运行时
     */
    private static JimiRuntime createRuntime() {
        // 创建 Agent
        Agent agent = Agent.builder()
                .name("context-demo-agent")
                .description("上下文持久化演示 Agent")
                .systemPrompt("你是一个友好的助手，能够记住之前的对话内容。")
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

        // 创建工作目录
        Path workDir = Paths.get(System.getProperty("user.dir")).resolve(".context-demo");
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            log.warn("创建工作目录失败: {}", e.getMessage());
        }

        return JimiRuntime.builder()
                .agent(agent)
                .llm(llm)
                .workDir(workDir)
                .build();
    }

    /**
     * 进行对话
     */
    private static void conductConversation(JimiRuntime runtime, String[] messages) {
        Context context = runtime.getContext();
        
        for (String msg : messages) {
            log.info("\n>>> 用户: {}", msg);
            
            runtime.getEngine().run(msg)
                    .doOnNext(result -> log.info("<<< Agent: {}", result.getResponse()))
                    .block();
        }

        // 显示当前上下文摘要
        List<Message> history = context.getHistory();
        log.info("  当前上下文消息数: {}", history.size());
        
        if (!history.isEmpty()) {
            Message lastMsg = history.get(history.size() - 1);
            log.info("  最后一条消息: {} - {}", lastMsg.getRole(), 
                    lastMsg.getContent().substring(0, Math.min(50, lastMsg.getContent().length())) + "...");
        }
    }
}
