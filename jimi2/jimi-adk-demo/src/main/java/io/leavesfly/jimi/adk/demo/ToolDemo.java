package io.leavesfly.jimi.adk.demo;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.core.JimiRuntime;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 工具演示示例
 * <p>
 * 演示如何创建和注册自定义工具，让 Agent 能够调用外部能力。
 * </p>
 */
@Slf4j
public class ToolDemo {

    public static void main(String[] args) {
        log.info("=== 工具演示示例 ===");

        // 创建自定义工具
        Tool<WeatherParams> weatherTool = createWeatherTool();
        Tool<Void> timeTool = createTimeTool();

        // 创建带工具的 Agent
        Agent agent = Agent.builder()
                .name("tool-agent")
                .description("配备了天气和时间查询工具的 Agent")
                .systemPrompt("""
                        你是一个助手，可以查询天气和当前时间。
                        当用户询问时间和天气时，请主动调用相应的工具。
                        """)
                .tools(List.of(weatherTool, timeTool))
                .build();

        log.info("✓ Agent 创建完成，工具数量: {}", agent.getTools().size());

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

        // 构建运行时（启用自动工具加载）
        JimiRuntime runtime = JimiRuntime.builder()
                .agent(agent)
                .llm(llm)
                .workDir(Paths.get(System.getProperty("user.dir")))
                .autoLoadTools(true)  // 启用 SPI 工具自动加载
                .build();

        // 测试工具调用
        String[] testCases = {
                "现在几点了？",
                "北京的天气怎么样？",
                "帮我查一下上海的时间和天气"
        };

        for (String userMessage : testCases) {
            log.info("\n>>> 用户: {}", userMessage);

            runtime.getEngine().run(userMessage)
                    .doOnNext(result -> log.info("<<< Agent: {}", result.getResponse()))
                    .block();
        }

        log.info("\n=== 示例完成 ===");
    }

    /**
     * 创建天气查询工具
     */
    private static Tool<WeatherParams> createWeatherTool() {
        return new Tool<>() {
            @Override
            public String getName() {
                return "get_weather";
            }

            @Override
            public String getDescription() {
                return "查询指定城市的天气信息";
            }

            @Override
            public Class<WeatherParams> getParamsType() {
                return WeatherParams.class;
            }

            @Override
            public Mono<ToolResult> execute(WeatherParams params) {
                log.info("→ 执行工具: {} (city={})", getName(), params.city);

                // 模拟天气查询（实际应调用天气 API）
                String weather = String.format(
                        "%s今天天气：晴，温度 22-28°C，空气质量良好，湿度 45%%，风力 3级",
                        params.city
                );

                return Mono.just(ToolResult.success(weather));
            }
        };
    }

    /**
     * 创建时间查询工具
     */
    private static Tool<Void> createTimeTool() {
        return new Tool<>() {
            @Override
            public String getName() {
                return "get_current_time";
            }

            @Override
            public String getDescription() {
                return "获取当前系统时间";
            }

            @Override
            public Class<Void> getParamsType() {
                return Void.class;
            }

            @Override
            public Mono<ToolResult> execute(Void params) {
                log.info("→ 执行工具: {}", getName());

                String time = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));

                return Mono.just(ToolResult.success("当前时间：" + time));
            }
        };
    }

    /**
     * 天气查询参数
     */
    public static class WeatherParams {
        public String city;
    }
}
