package io.leavesfly.jimi.adk.demo;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.api.session.Session;
import io.leavesfly.jimi.adk.core.JimiRuntime;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

/**
 * 会话管理演示示例
 * <p>
 * 演示如何创建和管理会话，实现用户隔离和会话状态跟踪。
 * </p>
 */
@Slf4j
public class SessionDemo {

    public static void main(String[] args) {
        log.info("=== 会话管理演示示例 ===");

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

        // 示例 1: 创建用户会话
        log.info("\n--- 示例 1: 用户会话 ---");
        Session user1Session = createSession("user-001", "张三");
        Session user2Session = createSession("user-002", "李四");

        log.info("✓ 创建会话 1: ID={}, 用户={}, 工作目录={}",
                user1Session.getId(), "张三", user1Session.getWorkDir());
        log.info("✓ 创建会话 2: ID={}, 用户={}, 工作目录={}",
                user2Session.getId(), "李四", user2Session.getWorkDir());

        // 示例 2: 不同用户的独立对话
        log.info("\n--- 示例 2: 独立对话 ---");
        
        // 用户 1 的对话
        Agent agent1 = createAgent("user1-agent", "用户1的 Agent");
        JimiRuntime runtime1 = JimiRuntime.builder()
                .agent(agent1)
                .llm(llm)
                .session(user1Session)
                .workDir(user1Session.getWorkDir())
                .build();

        log.info("[用户1] 开始对话...");
        runtime1.getEngine().run("你好，我是张三")
                .doOnNext(result -> log.info("[用户1] Agent: {}", 
                        result.getResponse().substring(0, 
                                Math.min(50, result.getResponse().length())) + "..."))
                .block();

        // 用户 2 的对话
        Agent agent2 = createAgent("user2-agent", "用户2的 Agent");
        JimiRuntime runtime2 = JimiRuntime.builder()
                .agent(agent2)
                .llm(llm)
                .session(user2Session)
                .workDir(user2Session.getWorkDir())
                .build();

        log.info("[用户2] 开始对话...");
        runtime2.getEngine().run("你好，我是李四")
                .doOnNext(result -> log.info("[用户2] Agent: {}", 
                        result.getResponse().substring(0, 
                                Math.min(50, result.getResponse().length())) + "..."))
                .block();

        // 示例 3: 会话状态检查
        log.info("\n--- 示例 3: 会话状态 ---");
        log.info("会话 1 状态: 有效={}, 创建时间={}, 最后活动={}",
                user1Session.isValid(),
                user1Session.getCreatedAt(),
                user1Session.getLastActivityAt());
        
        log.info("会话 2 状态: 有效={}, 创建时间={}, 最后活动={}",
                user2Session.isValid(),
                user2Session.getCreatedAt(),
                user2Session.getLastActivityAt());

        // 示例 4: 更新会话活动
        log.info("\n--- 示例 4: 更新会话活动 ---");
        user1Session.touch();
        log.info("✓ 更新会话 1 活动时间: {}", user1Session.getLastActivityAt());

        log.info("\n=== 示例完成 ===");
    }

    /**
     * 创建会话
     */
    private static Session createSession(String userId, String userName) {
        Path workDir = Paths.get(System.getProperty("user.dir"))
                .resolve(".sessions")
                .resolve(userId);

        return new SimpleSession(UUID.randomUUID().toString(), workDir, userName);
    }

    /**
     * 创建 Agent
     */
    private static Agent createAgent(String name, String description) {
        return Agent.builder()
                .name(name)
                .description(description)
                .systemPrompt("你是一个友好的助手，记住用户的名字和偏好。")
                .build();
    }

    /**
     * 简单会话实现
     */
    private static class SimpleSession implements Session {
        private final String id;
        private final Path workDir;
        private final String userName;
        private final Instant createdAt;
        private Instant lastActivityAt;
        private boolean valid;

        public SimpleSession(String id, Path workDir, String userName) {
            this.id = id;
            this.workDir = workDir;
            this.userName = userName;
            this.createdAt = Instant.now();
            this.lastActivityAt = createdAt;
            this.valid = true;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Path getWorkDir() {
            return workDir;
        }

        @Override
        public Instant getCreatedAt() {
            return createdAt;
        }

        @Override
        public Instant getLastActivityAt() {
            return lastActivityAt;
        }

        @Override
        public void touch() {
            this.lastActivityAt = Instant.now();
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        public String getUserName() {
            return userName;
        }
    }
}
