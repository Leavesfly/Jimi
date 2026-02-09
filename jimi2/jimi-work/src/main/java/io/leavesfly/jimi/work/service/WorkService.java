package io.leavesfly.jimi.work.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.agent.AgentSpec;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.engine.RuntimeConfig;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.api.message.ContentPart;
import io.leavesfly.jimi.adk.api.message.TextPart;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolProvider;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.api.wire.WireMessage;
import io.leavesfly.jimi.adk.core.context.DefaultContext;
import io.leavesfly.jimi.adk.core.engine.DefaultEngine;
import io.leavesfly.jimi.adk.core.tool.DefaultToolRegistry;
import io.leavesfly.jimi.adk.core.wire.DefaultWire;
import io.leavesfly.jimi.adk.core.wire.messages.*;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import io.leavesfly.jimi.work.config.WorkConfig;
import io.leavesfly.jimi.work.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JWork 核心服务
 * 管理多会话、任务执行、审批处理
 */
public class WorkService {

    private static final Logger log = LoggerFactory.getLogger(WorkService.class);
    private static final String SESSIONS_FILE = "sessions.json";
    private static final int MAX_SESSION_HISTORY = 100;

    /** LLM 实例 */
    private final LLM llm;
    /** 配置 */
    private final WorkConfig config;
    /** 活跃会话 */
    private final Map<String, WorkSession> sessions = new ConcurrentHashMap<>();
    /** 待处理审批 */
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    /** 持久化目录 */
    private final Path dataDir;
    /** JSON 序列化 */
    private final ObjectMapper objectMapper;

    public WorkService(WorkConfig config) {
        this.config = config;

        // 初始化 LLM
        LLMConfig llmConfig = config.toLLMConfig();
        LLMFactory llmFactory = new LLMFactory();
        this.llm = llmFactory.create(llmConfig);
        log.info("LLM 已初始化: provider={}, model={}", llmConfig.getProvider(), llmConfig.getModel());

        // 初始化数据目录
        this.dataDir = Paths.get(System.getProperty("user.home"), ".jimi");
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.warn("创建数据目录失败: {}", e.getMessage());
        }

        // 初始化 JSON
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .registerModule(new JavaTimeModule());
    }

    /**
     * 获取 LLM
     */
    public LLM getLlm() {
        return llm;
    }

    /**
     * 获取配置
     */
    public WorkConfig getConfig() {
        return config;
    }

    // ==================== 会话管理 ====================

    /**
     * 创建工作会话
     */
    public WorkSession createSession(Path workDir, String agentName) {
        log.info("创建会话: workDir={}, agent={}", workDir, agentName);

        // 创建引擎
        Engine engine = buildEngine(workDir, agentName);

        WorkSession session = new WorkSession(engine, workDir, agentName);
        sessions.put(session.getId(), session);

        // 持久化
        persistAllSessions();

        log.info("会话已创建: {}", session.getId());
        return session;
    }

    /**
     * 获取会话
     */
    public WorkSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 获取所有活跃会话
     */
    public List<WorkSession> getAllSessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * 关闭会话
     */
    public void closeSession(String sessionId) {
        WorkSession session = sessions.remove(sessionId);
        if (session != null) {
            if (session.isRunning()) {
                session.getEngine().interrupt();
                session.endJob();
            }
            persistAllSessions();
            log.info("会话已关闭: {}", sessionId);
        }
    }

    // ==================== 会话持久化 ====================

    /**
     * 持久化所有会话元数据
     */
    public void persistAllSessions() {
        List<SessionMetadata> allMetadata = new ArrayList<>(loadSessionMetadataList());

        for (WorkSession session : sessions.values()) {
            SessionMetadata meta = SessionMetadata.fromWorkSession(session);
            boolean found = false;
            for (int i = 0; i < allMetadata.size(); i++) {
                if (allMetadata.get(i).getId().equals(meta.getId())) {
                    allMetadata.set(i, meta);
                    found = true;
                    break;
                }
            }
            if (!found) {
                allMetadata.add(0, meta);
            }
        }

        if (allMetadata.size() > MAX_SESSION_HISTORY) {
            allMetadata = allMetadata.subList(0, MAX_SESSION_HISTORY);
        }

        Path sessionsFile = dataDir.resolve(SESSIONS_FILE);
        try {
            objectMapper.writeValue(sessionsFile.toFile(), allMetadata);
        } catch (IOException e) {
            log.error("持久化会话失败", e);
        }
    }

    /**
     * 加载会话元数据列表
     */
    public List<SessionMetadata> loadSessionMetadataList() {
        Path sessionsFile = dataDir.resolve(SESSIONS_FILE);
        if (!Files.exists(sessionsFile)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(sessionsFile.toFile(),
                    new TypeReference<List<SessionMetadata>>() {});
        } catch (IOException e) {
            log.error("加载会话元数据失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 恢复历史会话
     */
    public WorkSession restoreSession(SessionMetadata metadata) {
        log.info("恢复会话: id={}, workDir={}", metadata.getId(), metadata.getWorkDir());

        Path workDir = Paths.get(metadata.getWorkDir());
        Engine engine = buildEngine(workDir, metadata.getAgentName());

        WorkSession session = new WorkSession(
                metadata.getId(), engine, workDir,
                metadata.getAgentName(), metadata.getCreatedAt());
        sessions.put(session.getId(), session);
        persistAllSessions();

        log.info("会话已恢复: {}", session.getId());
        return session;
    }

    // ==================== 任务执行 ====================

    /**
     * 执行任务（流式输出）
     */
    public Flux<StreamChunk> execute(String sessionId, String input) {
        WorkSession session = sessions.get(sessionId);
        if (session == null) {
            return Flux.error(new IllegalArgumentException("会话不存在: " + sessionId));
        }
        if (session.isRunning()) {
            return Flux.error(new IllegalStateException("会话正在运行中"));
        }

        Engine engine = session.getEngine();
        String jobId = UUID.randomUUID().toString();
        session.startJob(jobId);

        Sinks.Many<StreamChunk> sink = Sinks.many().multicast().onBackpressureBuffer();
        CountDownLatch subscriptionReady = new CountDownLatch(1);

        Thread execThread = new Thread(() -> {
            reactor.core.Disposable subscription = null;
            try {
                // 订阅 Wire 消息流
                subscription = engine.getWire().asFlux()
                        .doOnSubscribe(s -> subscriptionReady.countDown())
                        .subscribe(msg -> {
                            StreamChunk chunk = convertWireToChunk(msg);
                            if (chunk != null) {
                                sink.tryEmitNext(chunk);
                            }
                        }, e -> {
                            log.error("Wire 错误", e);
                            sink.tryEmitNext(StreamChunk.error(e.getMessage()));
                        });

                subscriptionReady.await(1, TimeUnit.SECONDS);
                Thread.sleep(50);

                // 执行
                ExecutionResult result = engine.run(input).block();

                Thread.sleep(200);

                if (result != null && !result.isSuccess()) {
                    sink.tryEmitNext(StreamChunk.error(
                            result.getError() != null ? result.getError() : "执行失败"));
                }

                log.info("任务完成: jobId={}", jobId);
            } catch (Exception e) {
                log.error("任务异常: jobId={}", jobId, e);
                sink.tryEmitNext(StreamChunk.error(e.getMessage()));
            } finally {
                if (subscription != null) {
                    subscription.dispose();
                }
                session.endJob();
                sink.tryEmitNext(StreamChunk.done());
                sink.tryEmitComplete();
            }
        }, "jwork-exec-" + jobId);
        execThread.setDaemon(true);
        execThread.start();

        return sink.asFlux();
    }

    /**
     * 取消任务
     */
    public void cancelTask(String sessionId) {
        WorkSession session = sessions.get(sessionId);
        if (session != null && session.isRunning()) {
            session.getEngine().interrupt();
            session.endJob();
            log.info("任务已取消: sessionId={}", sessionId);
        }
    }

    // ==================== 审批处理 ====================

    /**
     * 处理审批
     */
    public void handleApproval(String toolCallId, ApprovalInfo.Response response) {
        PendingApproval pending = pendingApprovals.remove(toolCallId);
        if (pending == null) {
            log.warn("未找到审批请求: {}", toolCallId);
            return;
        }
        // TODO: 集成 ADK Approval 机制
        log.info("审批已处理: toolCallId={}, response={}", toolCallId, response);
    }

    // ==================== Agent 列表 ====================

    /**
     * 获取可用的 Agent 列表
     */
    public List<String> getAvailableAgents() {
        return List.of("default", "code", "architect", "doc", "devops", "quality");
    }

    // ==================== 关闭 ====================

    /**
     * 关闭服务
     */
    public void shutdown() {
        log.info("关闭 WorkService...");
        for (String sessionId : sessions.keySet()) {
            try {
                cancelTask(sessionId);
            } catch (Exception e) {
                log.warn("取消任务失败: {}", sessionId, e);
            }
        }
        sessions.clear();
        pendingApprovals.clear();
        log.info("WorkService 已关闭");
    }

    // ==================== 私有方法 ====================

    /**
     * 构建引擎
     */
    @SuppressWarnings("unchecked")
    private Engine buildEngine(Path workDir, String agentName) {
        Wire wire = new DefaultWire();
        Context context = new DefaultContext();
        ObjectMapper engineMapper = new ObjectMapper();
        ToolRegistry toolRegistry = new DefaultToolRegistry(engineMapper);

        // 加载 Agent 和工具
        AgentSpec agentSpec = AgentSpec.builder()
                .name(agentName != null ? agentName : "default")
                .description("Jimi Work Agent")
                .version("2.0.0")
                .build();

        RuntimeConfig toolConfig = RuntimeConfig.builder().workDir(workDir).build();
        Runtime toolRuntime = Runtime.builder().config(toolConfig).build();
        List<Tool> tools = new ArrayList<>();
        for (ToolProvider provider : ServiceLoader.load(ToolProvider.class)) {
            if (provider.supports(agentSpec, toolRuntime)) {
                for (Tool<?> t : provider.createTools(agentSpec, toolRuntime)) {
                    tools.add((Tool) t);
                }
            }
        }
        for (Tool tool : tools) {
            toolRegistry.register(tool);
        }

        // 构建工具描述
        StringBuilder toolDesc = new StringBuilder();
        for (Tool tool : tools) {
            toolDesc.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }

        Agent agent = Agent.builder()
                .name(agentName != null ? agentName : "default")
                .description("Jimi Work Agent")
                .version("2.0.0")
                .systemPrompt("你是 Jimi，一个强大的 AI 编程助手。\n"
                        + "你有以下工具可用：\n" + toolDesc
                        + "请始终使用清晰、专业的语言，并在执行危险操作前确认。")
                .tools(tools)
                .maxSteps(100)
                .build();

        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .workDir(workDir)
                .yoloMode(config.isYoloMode())
                .maxContextTokens(config.getMaxContextTokens())
                .build();
        
        Runtime runtime = Runtime.builder()
                .llm(llm)
                .config(runtimeConfig)
                .build();

        return DefaultEngine.builder()
                .agent(agent)
                .runtime(runtime)
                .context(context)
                .toolRegistry(toolRegistry)
                .wire(wire)
                .build();
    }

    /**
     * Wire 消息转换为 StreamChunk
     */
    private StreamChunk convertWireToChunk(WireMessage msg) {
        if (msg instanceof ContentPartMessage cpm) {
            ContentPart part = cpm.getContentPart();
            if (part instanceof TextPart tp) {
                if (cpm.getContentType() == ContentPartMessage.ContentType.REASONING) {
                    return StreamChunk.reasoning(tp.getText());
                }
                return StreamChunk.text(tp.getText());
            }
        } else if (msg instanceof ToolCallMessage tcm) {
            String toolName = tcm.getToolCall().getFunction().getName();
            String toolCallId = tcm.getToolCall().getId();
            return StreamChunk.toolCall(toolName, toolCallId);
        } else if (msg instanceof ToolResultMessage trm) {
            String content = trm.getToolResult() != null ? trm.getToolResult().getMessage() : "";
            return StreamChunk.toolResult(trm.getToolName(), content);
        } else if (msg instanceof StepBegin sb) {
            return StreamChunk.stepBegin(sb.getStepNumber());
        } else if (msg instanceof StepEnd se) {
            return StreamChunk.stepEnd(se.getStepNumber());
        }
        return null;
    }

    /**
     * 待处理审批
     */
    private record PendingApproval(String toolCallId, String jobId) {}
}
