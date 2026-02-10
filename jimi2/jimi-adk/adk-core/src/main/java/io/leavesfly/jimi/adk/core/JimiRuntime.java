package io.leavesfly.jimi.adk.core;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.agent.AgentSpec;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.engine.InteractionContext;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.engine.RuntimeConfig;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.session.Session;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolProvider;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.core.context.DefaultContext;
import io.leavesfly.jimi.adk.core.engine.DefaultEngine;
import io.leavesfly.jimi.adk.core.engine.compaction.Compaction;
import io.leavesfly.jimi.adk.core.tool.DefaultToolRegistry;
import io.leavesfly.jimi.adk.core.wire.DefaultWire;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Jimi 运行时 Bootstrap 工厂
 * <p>
 * 封装 Engine + ToolRegistry + Context + Wire + LLM 的标准组装流程，
 * 各应用端（CLI、Web、Desktop、IntelliJ）统一使用此工厂创建运行时。
 * </p>
 *
 * <p>用法示例：
 * <pre>{@code
 * // 1. 加载配置（统一由 JimiConfig 负责 API Key 解析等配置职责）
 * JimiConfig config = JimiConfig.load(workDir);
 *
 * // 2. 通过配置创建 LLM（LLMFactory 信任已解析的配置，不再重复解析环境变量）
 * LLM llm = new LLMFactory().create(config.toLLMConfig());
 *
 * // 3. 构建运行时
 * JimiRuntime runtime = JimiRuntime.builder()
 *     .agent(agent)
 *     .llm(llm)
 *     .workDir(workDir)
 *     .maxContextTokens(config.getMaxContextTokens())
 *     .build();
 *
 * runtime.getEngine().run("hello").block();
 * }</pre>
 */
@Slf4j
@Getter
public class JimiRuntime {

    /** 执行引擎 */
    private final Engine engine;

    /** 消息总线 */
    private final Wire wire;

    /** 对话上下文 */
    private final Context context;

    /** 工具注册表 */
    private final ToolRegistry toolRegistry;

    /** 运行时环境 */
    private final Runtime runtime;

    /** 当前 Agent */
    private final Agent agent;

    private JimiRuntime(Engine engine, Wire wire, Context context,
                        ToolRegistry toolRegistry, Runtime runtime, Agent agent) {
        this.engine = engine;
        this.wire = wire;
        this.context = context;
        this.toolRegistry = toolRegistry;
        this.runtime = runtime;
        this.agent = agent;
    }

    /**
     * 创建 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * JimiRuntime 构建器
     */
    public static class Builder {

        private Agent agent;
        private LLM llm;
        private Path workDir;
        private int maxContextTokens = 100000;
        private Session session;
        private InteractionContext interactionContext;
        private Context context;
        private Wire wire;
        private ToolRegistry toolRegistry;
        private Compaction compaction;
        private boolean autoLoadTools = true;

        public Builder agent(Agent agent) {
            this.agent = agent;
            return this;
        }

        public Builder llm(LLM llm) {
            this.llm = llm;
            return this;
        }

        public Builder workDir(Path workDir) {
            this.workDir = workDir;
            return this;
        }

        public Builder maxContextTokens(int maxContextTokens) {
            this.maxContextTokens = maxContextTokens;
            return this;
        }

        public Builder session(Session session) {
            this.session = session;
            return this;
        }

        public Builder interactionContext(InteractionContext interactionContext) {
            this.interactionContext = interactionContext;
            return this;
        }

        /** 提供自定义 Context（默认使用 DefaultContext） */
        public Builder context(Context context) {
            this.context = context;
            return this;
        }

        /** 提供自定义 Wire（默认使用 DefaultWire） */
        public Builder wire(Wire wire) {
            this.wire = wire;
            return this;
        }

        /** 提供自定义 ToolRegistry（默认使用 DefaultToolRegistry） */
        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        /** 设置上下文压缩器 */
        public Builder compaction(Compaction compaction) {
            this.compaction = compaction;
            return this;
        }

        /** 是否自动通过 SPI 加载工具（默认 true） */
        public Builder autoLoadTools(boolean autoLoadTools) {
            this.autoLoadTools = autoLoadTools;
            return this;
        }

        /**
         * 构建 JimiRuntime
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public JimiRuntime build() {
            // 1. 基础组件（使用默认值或自定义值）
            Wire wire = this.wire != null ? this.wire : new DefaultWire();
            Context context = this.context != null ? this.context : new DefaultContext();
            ObjectMapper objectMapper = new ObjectMapper();
            ToolRegistry toolRegistry = this.toolRegistry != null
                    ? this.toolRegistry
                    : new DefaultToolRegistry(objectMapper);

            // 2. 构建 RuntimeConfig（workDir 优先使用显式设置值，否则从 Session 派生）
            Path resolvedWorkDir = this.workDir;
            if (resolvedWorkDir == null && this.session != null) {
                resolvedWorkDir = this.session.getWorkDir();
            }
            RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                    .workDir(resolvedWorkDir)
                    .maxContextTokens(maxContextTokens)
                    .build();

            InteractionContext interaction = this.interactionContext != null
                    ? this.interactionContext
                    : new InteractionContext();

            // 3. 构建 Runtime
            Runtime runtime = Runtime.builder()
                    .llm(llm)
                    .session(session)
                    .config(runtimeConfig)
                    .interaction(interaction)
                    .build();

            // 4. 自动加载工具（SPI）
            if (autoLoadTools && agent != null) {
                AgentSpec agentSpec = AgentSpec.builder()
                        .name(agent.getName())
                        .build();

                List<Tool> spiTools = new ArrayList<>();
                for (ToolProvider provider : ServiceLoader.load(ToolProvider.class)) {
                    if (provider.supports(agentSpec, runtime)) {
                        List<Tool<?>> tools = provider.createTools(agentSpec, runtime);
                        for (Tool<?> tool : tools) {
                            spiTools.add((Tool) tool);
                        }
                    }
                }

                // 注册 SPI 工具
                for (Tool tool : spiTools) {
                    toolRegistry.register(tool);
                }

                // 注册 Agent 自带的工具
                if (agent.getTools() != null) {
                    for (Tool<?> tool : agent.getTools()) {
                        toolRegistry.register(tool);
                    }
                }

                log.info("已加载 {} 个工具", toolRegistry.getToolNames().size());
            }

            // 5. 构建 Engine
            Engine engine = DefaultEngine.builder()
                    .agent(agent)
                    .runtime(runtime)
                    .context(context)
                    .toolRegistry(toolRegistry)
                    .wire(wire)
                    .compaction(compaction)
                    .build();

            log.info("JimiRuntime 已构建: agent={}, llm={}/{}",
                    agent != null ? agent.getName() : "null",
                    llm != null ? llm.getProvider() : "null",
                    llm != null ? llm.getModel() : "null");

            return new JimiRuntime(engine, wire, context, toolRegistry, runtime, agent);
        }
    }
}
