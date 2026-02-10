package io.leavesfly.jimi.adk.demo;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.core.JimiRuntime;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.file.Paths;
import java.time.Duration;

/**
 * 错误处理演示示例
 * <p>
 * 演示如何处理各种错误情况，包括网络错误、API 限制、超时等。
 * </p>
 */
@Slf4j
public class ErrorHandlingDemo {

    public static void main(String[] args) {
        log.info("=== 错误处理演示示例 ===");

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

        Agent agent = Agent.builder()
                .name("error-handling-agent")
                .description("错误处理演示 Agent")
                .systemPrompt("你是一个测试助手，用于演示错误处理。")
                .build();

        // 示例 1: 基本错误处理
        log.info("\n--- 示例 1: 基本错误处理 ---");
        demoBasicErrorHandling(agent, llm);

        // 示例 2: 降级处理
        log.info("\n--- 示例 2: 降级处理 ---");
        demoFallback(agent, llm);

        // 示例 3: 重试与退避
        log.info("\n--- 示例 3: 重试与退避 ---");
        demoRetryWithBackoff(agent, llm);

        // 示例 4: 错误恢复
        log.info("\n--- 示例 4: 错误恢复 ---");
        demoErrorRecovery(agent, llm);

        log.info("\n=== 示例完成 ===");
    }

    /**
     * 基本错误处理
     */
    private static void demoBasicErrorHandling(Agent agent, LLM llm) {
        JimiRuntime runtime = createRuntime(agent, llm, "basic");

        runtime.getEngine().run("你好")
                .doOnSubscribe(s -> log.info("[基本] 开始执行..."))
                .doOnSuccess(result -> log.info("[基本] 成功: {}", 
                        result.isSuccess() ? "是" : "否"))
                .doOnError(e -> log.error("[基本] 错误: {}", e.getMessage()))
                .onErrorResume(e -> {
                    log.warn("[基本] 捕获错误，返回默认值");
                    return Mono.just(ExecutionResult.error("执行失败: " + e.getMessage()));
                })
                .block();
    }

    /**
     * 降级处理示例
     */
    private static void demoFallback(Agent agent, LLM llm) {
        JimiRuntime runtime = createRuntime(agent, llm, "fallback");

        String question = "解释什么是微服务架构";

        log.info("[降级] 发送问题: {}", question);

        runtime.getEngine().run(question)
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(e -> {
                    log.warn("[降级] 主请求失败，使用降级策略");
                    // 降级：返回简化回答
                    return Mono.just(ExecutionResult.success(
                            "微服务架构是一种将应用拆分为小型、独立服务的架构风格。" +
                            "（这是降级回答）",
                            1, 0));
                })
                .doOnNext(result -> log.info("[降级] 结果: {}", 
                        result.getResponse().substring(0, 
                                Math.min(50, result.getResponse().length())) + "..."))
                .block();
    }

    /**
     * 重试与退避示例
     */
    private static void demoRetryWithBackoff(Agent agent, LLM llm) {
        JimiRuntime runtime = createRuntime(agent, llm, "retry");

        log.info("[重试] 发送请求（带指数退避重试）...");

        runtime.getEngine().run("什么是设计模式？")
                .retryWhen(reactor.util.retry.Retry
                        .backoff(3, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(5))
                        .doBeforeRetry(retrySignal -> 
                            log.info("[重试] 第 {} 次重试...", retrySignal.totalRetries() + 1)))
                .doOnNext(result -> log.info("[重试] 成功"))
                .doOnError(e -> log.error("[重试] 最终失败: {}", e.getMessage()))
                .onErrorReturn(ExecutionResult.error("重试后仍失败"))
                .block();
    }

    /**
     * 错误恢复示例
     */
    private static void demoErrorRecovery(Agent agent, LLM llm) {
        JimiRuntime runtime = createRuntime(agent, llm, "recovery");

        log.info("[恢复] 发送请求...");

        runtime.getEngine().run("列举 5 种常见的设计模式")
                .flatMap(result -> {
                    if (!result.isSuccess()) {
                        log.warn("[恢复] 检测到失败，尝试恢复...");
                        // 尝试恢复：使用更简单的提示
                        return runtime.getEngine().run("什么是单例模式？");
                    }
                    return Mono.just(result);
                })
                .doOnError(e -> log.error("[恢复] 恢复失败: {}", e.getMessage()))
                .onErrorReturn(ExecutionResult.error("无法恢复"))
                .doOnNext(result -> log.info("[恢复] 最终结果: {}", 
                        result.isSuccess() ? "成功" : "失败"))
                .block();
    }

    /**
     * 创建运行时
     */
    private static JimiRuntime createRuntime(Agent agent, LLM llm, String suffix) {
        return JimiRuntime.builder()
                .agent(agent)
                .llm(llm)
                .workDir(Paths.get(System.getProperty("user.dir"))
                        .resolve(".error-demo-" + suffix))
                .build();
    }
}
