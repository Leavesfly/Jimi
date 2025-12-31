package io.leavesfly.jimi.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.core.agent.AgentRegistry;
import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.config.JimiConfig;
import io.leavesfly.jimi.core.compaction.Compaction;

import io.leavesfly.jimi.knowledge.KnowledgeService;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.LLMFactory;
import io.leavesfly.jimi.core.session.Session;
import io.leavesfly.jimi.core.session.SessionManager;
import io.leavesfly.jimi.core.agent.Agent;
import io.leavesfly.jimi.core.interaction.approval.Approval;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.core.engine.runtime.Runtime;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolRegistryFactory;
import io.leavesfly.jimi.wire.Wire;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

import java.util.List;

/**
 * Jimi 应用工厂（Spring Service）
 * 负责组装所有核心组件，创建完整的 Jimi 实例
 */
@Slf4j
@Service
public class JimiFactory {

    // ==================== 核心依赖 ====================
    @Autowired
    private JimiConfig config;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AgentRegistry agentRegistry;
    @Autowired
    private ToolRegistryFactory toolRegistryFactory;
    @Autowired
    private LLMFactory llmFactory;
    @Autowired
    private SessionManager sessionManager;
    @Autowired
    private Wire wire;  // 注入 Wire Bean，避免 new 创建
    @Autowired
    private Compaction compaction;  // 注入 Compaction Bean

    // ==================== 组件提供者（封装可选依赖） ====================
    @Autowired
    private KnowledgeService knowledgeService;


    // ==================== Builder 模式 API ====================

    /**
     * 创建 Engine 构建器
     * <p>
     * 使用示例：
     * <pre>
     * jimiFactory.createEngine()
     *     .session(session)
     *     .agentSpec(agentSpecPath)     // 可选
     *     .model("gpt-4")               // 可选
     *     .yolo(true)                   // 可选，默认 false
     *     .mcpConfigs(mcpConfigFiles)   // 可选
     *     .build();
     * </pre>
     */
    public EngineBuilder createEngine() {
        return new EngineBuilder(this);
    }

    /**
     * Engine 构建器
     */
    public static class EngineBuilder {
        private final JimiFactory factory;

        // 必需参数
        private Session session;

        // 可选参数（有默认值）
        private Path agentSpecPath;
        private String modelName;
        private boolean yolo = false;
        private List<Path> mcpConfigFiles;

        private EngineBuilder(JimiFactory factory) {
            this.factory = factory;
        }

        /**
         * 设置会话（必需）
         */
        public EngineBuilder session(Session session) {
            this.session = session;
            return this;
        }

        /**
         * 设置 Agent 规范文件路径（可选，默认使用默认 Agent）
         */
        public EngineBuilder agentSpec(Path agentSpecPath) {
            this.agentSpecPath = agentSpecPath;
            return this;
        }

        /**
         * 设置模型名称（可选，优先级：此参数 > Agent配置 > 全局默认）
         */
        public EngineBuilder model(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 设置 YOLO 模式（可选，默认 false）
         * <p>YOLO 模式会自动批准所有操作</p>
         */
        public EngineBuilder yolo(boolean yolo) {
            this.yolo = yolo;
            return this;
        }

        /**
         * 设置 MCP 配置文件列表（可选）
         */
        public EngineBuilder mcpConfigs(List<Path> mcpConfigFiles) {
            this.mcpConfigFiles = mcpConfigFiles;
            return this;
        }

        /**
         * 构建 JimiEngine 实例
         */
        public Mono<JimiEngine> build() {
            if (session == null) {
                return Mono.error(new IllegalArgumentException("session is required"));
            }
            return factory.doCreateEngine(session, agentSpecPath, modelName, yolo, mcpConfigFiles);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 内部方法：创建 JimiEngine 实例
     */
    private Mono<JimiEngine> doCreateEngine(
            Session session,
            Path agentSpecPath,
            String modelName,
            boolean yolo,
            List<Path> mcpConfigFiles) {

        return Mono.defer(() -> {
            try {
                log.debug("Creating Jimi Engine for session: {}", session.getId());

                // 1. 加载 Agent 规范（优先加载以获取可能的 model 配置）
                AgentSpec agentSpec = agentRegistry.loadAgentSpec(agentSpecPath).block();

                // 2. 决定最终使用的模型（优先级：命令行参数 > Agent配置 > 全局默认配置）
                String effectiveModelName = modelName;
                if (effectiveModelName == null && agentSpec.getModel() != null) {
                    effectiveModelName = agentSpec.getModel();
                    log.info("使用Agent配置的模型: {}", effectiveModelName);
                }

                // 3. 获取或创建 LLM（使用工厂，带缓存）
                LLM llm = llmFactory.getOrCreateLLM(effectiveModelName);

                // 4. 创建 Runtime（自动构建 builtinArgs）
                Approval approval = new Approval(yolo);

                Runtime runtime = Runtime.builder()
                        .config(config)
                        .llm(llm)
                        .session(session)
                        .approval(approval)
                        .sessionManager(sessionManager)  // 用于构建 builtinArgs
                        .build();

                // 5. 使用 AgentRegistry 单例加载 Agent（包含系统提示词处理）
                Agent agent = agentSpecPath != null
                        ? agentRegistry.loadAgent(agentSpecPath, runtime).block()
                        : agentRegistry.loadDefaultAgent(runtime).block();
                if (agent == null) {
                    String msg = agentSpecPath != null
                            ? "Failed to load agent from: " + agentSpecPath
                            : "Failed to load default agent";
                    throw new RuntimeException(msg);
                }

                // 6. 创建 Context 并恢复历史
                // 注意：可以通过 new Context(file, mapper, true) 启用异步批量Repository以提升性能
                Context context = new Context(session.getHistoryFile(), objectMapper);

                // 7. 创建 ToolRegistry（委托给 ToolRegistryFactory）
                ToolRegistry toolRegistry = toolRegistryFactory.create(
                        runtime.getBuiltinArgs(), approval, agentSpec, runtime, mcpConfigFiles);

                knowledgeService.initialize(runtime);

                // 8. 创建 JimiEngine（使用 Builder 模式）
                JimiEngine soul = JimiEngine.builder()
                        .agent(agent)
                        .runtime(runtime)
                        .context(context)
                        .toolRegistry(toolRegistry)
                        .wire(wire)  // 使用注入的 Wire Bean
                        .compaction(compaction)  // 使用注入的 Compaction Bean
                        .build();

                // 9. 恢复上下文历史
                return context.restore()
                        .then(Mono.just(soul))
                        .doOnSuccess(s -> log.info("Jimi Engine created successfully"));

            } catch (Exception e) {
                log.error("Failed to create Jimi Engine", e);
                return Mono.error(e);
            }
        });
    }

    // ==================== 兼容旧 API（过渡期保留） ====================

    /**
     * @deprecated 使用 {@link #createEngine()} Builder 模式代替
     */
    @Deprecated
    public Mono<JimiEngine> createSoul(
            Session session,
            Path agentSpecPath,
            String modelName,
            boolean yolo,
            List<Path> mcpConfigFiles) {
        return doCreateEngine(session, agentSpecPath, modelName, yolo, mcpConfigFiles);
    }
}
