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
 * 多 Agent 协作演示示例
 * <p>
 * 演示如何让多个 Agent 协作完成复杂任务，包括任务分解、结果汇总等。
 * </p>
 */
@Slf4j
public class MultiAgentDemo {

    public static void main(String[] args) {
        log.info("=== 多 Agent 协作演示示例 ===");

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

        // 示例 1: 任务分解与协作
        log.info("\n--- 示例 1: 任务分解与协作 ---");
        demoTaskDecomposition(llm);

        // 示例 2: 专家咨询模式
        log.info("\n--- 示例 2: 专家咨询模式 ---");
        demoExpertConsultation(llm);

        // 示例 3: 结果汇总
        log.info("\n--- 示例 3: 结果汇总 ---");
        demoResultAggregation(llm);

        log.info("\n=== 示例完成 ===");
    }

    /**
     * 任务分解与协作示例
     */
    private static void demoTaskDecomposition(LLM llm) {
        // 创建不同角色的 Agent
        Agent planner = Agent.builder()
                .name("planner")
                .description("任务规划专家")
                .systemPrompt("你是一个任务规划专家，擅长将复杂任务分解为可执行的步骤。")
                .build();

        Agent executor = Agent.builder()
                .name("executor")
                .description("执行专家")
                .systemPrompt("你是一个执行专家，擅长完成具体的子任务。")
                .build();

        Agent reviewer = Agent.builder()
                .name("reviewer")
                .description("审核专家")
                .systemPrompt("你是一个审核专家，擅长检查结果的质量和完整性。")
                .build();

        // 创建各自的运行时（共享 Context 实现协作）
        JimiRuntime plannerRuntime = createRuntime(planner, llm, "planner");
        JimiRuntime executorRuntime = createRuntime(executor, llm, "executor");
        JimiRuntime reviewerRuntime = createRuntime(reviewer, llm, "reviewer");

        String task = "设计一个简单的博客系统";
        log.info("[协作] 任务: {}", task);

        // 步骤 1: 规划
        log.info("[协作] 步骤 1: 规划...");
        String plan = plannerRuntime.getEngine()
                .run("请将以下任务分解为步骤: " + task)
                .map(result -> {
                    log.info("[协作] 规划结果: {}...", 
                            result.getResponse().substring(0, 
                                    Math.min(50, result.getResponse().length())));
                    return result.getResponse();
                })
                .block();

        // 步骤 2: 执行
        log.info("[协作] 步骤 2: 执行...");
        String execution = executorRuntime.getEngine()
                .run("请执行以下规划: " + plan)
                .map(result -> {
                    log.info("[协作] 执行结果: {}...", 
                            result.getResponse().substring(0, 
                                    Math.min(50, result.getResponse().length())));
                    return result.getResponse();
                })
                .block();

        // 步骤 3: 审核
        log.info("[协作] 步骤 3: 审核...");
        reviewerRuntime.getEngine()
                .run("请审核以下结果: " + execution)
                .doOnNext(result -> log.info("[协作] 审核意见: {}...", 
                        result.getResponse().substring(0, 
                                Math.min(50, result.getResponse().length()))))
                .block();

        log.info("✓ 协作完成");
    }

    /**
     * 专家咨询模式示例
     */
    private static void demoExpertConsultation(LLM llm) {
        // 创建不同领域的专家
        Agent techExpert = Agent.builder()
                .name("tech-expert")
                .description("技术专家")
                .systemPrompt("你是技术专家，专注于技术实现和架构设计。")
                .build();

        Agent businessExpert = Agent.builder()
                .name("business-expert")
                .description("业务专家")
                .systemPrompt("你是业务专家，专注于业务需求和用户体验。")
                .build();

        Agent securityExpert = Agent.builder()
                .name("security-expert")
                .description("安全专家")
                .systemPrompt("你是安全专家，专注于系统安全和数据保护。")
                .build();

        JimiRuntime techRuntime = createRuntime(techExpert, llm, "tech");
        JimiRuntime businessRuntime = createRuntime(businessExpert, llm, "business");
        JimiRuntime securityRuntime = createRuntime(securityExpert, llm, "security");

        String topic = "开发一个电商支付系统";
        log.info("[咨询] 主题: {}", topic);

        // 并行咨询多位专家
        log.info("[咨询] 并行咨询三位专家...");

        String techAdvice = techRuntime.getEngine()
                .run("从技术角度分析: " + topic)
                .map(result -> result.getResponse().substring(0, 
                        Math.min(100, result.getResponse().length())))
                .block();

        String businessAdvice = businessRuntime.getEngine()
                .run("从业务角度分析: " + topic)
                .map(result -> result.getResponse().substring(0, 
                        Math.min(100, result.getResponse().length())))
                .block();

        String securityAdvice = securityRuntime.getEngine()
                .run("从安全角度分析: " + topic)
                .map(result -> result.getResponse().substring(0, 
                        Math.min(100, result.getResponse().length())))
                .block();

        log.info("[咨询] 技术专家: {}...", techAdvice);
        log.info("[咨询] 业务专家: {}...", businessAdvice);
        log.info("[咨询] 安全专家: {}...", securityAdvice);

        log.info("✓ 专家咨询完成");
    }

    /**
     * 结果汇总示例
     */
    private static void demoResultAggregation(LLM llm) {
        Agent aggregator = Agent.builder()
                .name("aggregator")
                .description("汇总专家")
                .systemPrompt("你是汇总专家，擅长整合多个来源的信息。")
                .build();

        JimiRuntime aggregatorRuntime = createRuntime(aggregator, llm, "aggregator");

        // 模拟多个子任务的结果
        List<String> subResults = List.of(
                "前端: 使用 React 构建用户界面，支持响应式设计",
                "后端: 使用 Spring Boot 提供 REST API，使用 MySQL 存储数据",
                "部署: 使用 Docker 容器化，部署到 Kubernetes 集群"
        );

        log.info("[汇总] 子任务结果:");
        subResults.forEach(r -> log.info("  - {}", r));

        // 汇总结果
        String summaryPrompt = "请汇总以下技术方案:\n" + String.join("\n", subResults);
        
        aggregatorRuntime.getEngine()
                .run(summaryPrompt)
                .doOnNext(result -> log.info("[汇总] 综合方案: {}...", 
                        result.getResponse().substring(0, 
                                Math.min(150, result.getResponse().length()))))
                .block();

        log.info("✓ 结果汇总完成");
    }

    /**
     * 创建运行时
     */
    private static JimiRuntime createRuntime(Agent agent, LLM llm, String suffix) {
        return JimiRuntime.builder()
                .agent(agent)
                .llm(llm)
                .workDir(Paths.get(System.getProperty("user.dir"))
                        .resolve(".multi-agent-demo-" + suffix))
                .build();
    }
}
