package io.leavesfly.jimi.adk.demo;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.core.JimiRuntime;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 异步执行演示示例
 * <p>
 * 演示如何使用 Reactor 进行异步执行、并发处理和流式响应。
 * </p>
 */
@Slf4j
public class AsyncDemo {

    public static void main(String[] args) throws InterruptedException {
        log.info("=== 异步执行演示示例 ===");

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

        // 示例 1: 顺序执行
        log.info("\n--- 示例 1: 顺序执行 ---");
        demoSequentialExecution(llm);

        // 示例 2: 并行执行
        log.info("\n--- 示例 2: 并行执行 ---");
        demoParallelExecution(llm);

        // 示例 3: 超时控制
        log.info("\n--- 示例 3: 超时控制 ---");
        demoTimeoutControl(llm);

        // 示例 4: 重试机制
        log.info("\n--- 示例 4: 重试机制 ---");
        demoRetryMechanism(llm);

        log.info("\n=== 示例完成 ===");
    }

    /**
     * 顺序执行示例
     */
    private static void demoSequentialExecution(LLM llm) {
        Agent agent = createAgent("sequential-agent", "顺序执行 Agent");
        JimiRuntime runtime = createRuntime(agent, llm, "sequential");

        List<String> questions = List.of(
                "什么是 Java？",
                "Java 有哪些主要特性？",
                "Java 和 Python 有什么区别？"
        );

        // 使用 concatMap 保证顺序执行
        Flux.fromIterable(questions)
                .concatMap(question -> {
                    log.info("[顺序] 发送: {}", question);
                    return runtime.getEngine().run(question)
                            .doOnNext(result -> log.info("[顺序] 收到回复 ({} 字符)", 
                                    result.getResponse().length()));
                })
                .collectList()
                .block();

        log.info("✓ 顺序执行完成");
    }

    /**
     * 并行执行示例
     */
    private static void demoParallelExecution(LLM llm) throws InterruptedException {
        Agent agent = createAgent("parallel-agent", "并行执行 Agent");
        JimiRuntime runtime = createRuntime(agent, llm, "parallel");

        List<String> questions = List.of(
                "解释什么是 REST API",
                "什么是 GraphQL？",
                "比较 gRPC 和 REST"
        );

        CountDownLatch latch = new CountDownLatch(questions.size());

        // 使用 flatMap 实现并行执行
        Flux.fromIterable(questions)
                .flatMap(question -> {
                    log.info("[并行] 发送: {}", question);
                    return runtime.getEngine().run(question)
                            .subscribeOn(Schedulers.boundedElastic())
                            .doOnNext(result -> {
                                log.info("[并行] 收到回复 ({} 字符)", 
                                        result.getResponse().length());
                                latch.countDown();
                            });
                }, 3) // 并发度为 3
                .collectList()
                .block();

        latch.await();
        log.info("✓ 并行执行完成");
    }

    /**
     * 超时控制示例
     */
    private static void demoTimeoutControl(LLM llm) {
        Agent agent = createAgent("timeout-agent", "超时控制 Agent");
        JimiRuntime runtime = createRuntime(agent, llm, "timeout");

        String longQuestion = "请详细解释 Java 的内存模型，包括堆、栈、方法区等";

        log.info("[超时] 发送长问题...");

        runtime.getEngine().run(longQuestion)
                .timeout(Duration.ofSeconds(5)) // 5 秒超时
                .doOnNext(result -> log.info("[超时] 收到回复"))
                .doOnError(e -> {
                    if (e instanceof java.util.concurrent.TimeoutException) {
                        log.warn("[超时] 请求超时！");
                    } else {
                        log.error("[超时] 错误: {}", e.getMessage());
                    }
                })
                .onErrorReturn(ExecutionResult.error("请求超时"))
                .block();

        log.info("✓ 超时控制演示完成");
    }

    /**
     * 重试机制示例
     */
    private static void demoRetryMechanism(LLM llm) {
        Agent agent = createAgent("retry-agent", "重试机制 Agent");
        JimiRuntime runtime = createRuntime(agent, llm, "retry");

        String question = "什么是响应式编程？";

        log.info("[重试] 发送问题（带重试）...");

        runtime.getEngine().run(question)
                .retry(3) // 重试 3 次
                .doOnNext(result -> log.info("[重试] 成功收到回复"))
                .doOnError(e -> log.error("[重试] 最终失败: {}", e.getMessage()))
                .onErrorReturn(ExecutionResult.error("重试后仍失败"))
                .block();

        log.info("✓ 重试机制演示完成");
    }

    /**
     * 创建 Agent
     */
    private static Agent createAgent(String name, String description) {
        return Agent.builder()
                .name(name)
                .description(description)
                .systemPrompt("你是一个技术专家，擅长简洁明了地解释技术概念。")
                .build();
    }

    /**
     * 创建运行时
     */
    private static JimiRuntime createRuntime(Agent agent, LLM llm, String suffix) {
        return JimiRuntime.builder()
                .agent(agent)
                .llm(llm)
                .workDir(Paths.get(System.getProperty("user.dir"))
                        .resolve(".async-demo-" + suffix))
                .build();
    }
}
