package io.leavesfly.jimi.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.config.JimiConfig;
import io.leavesfly.jimi.adk.core.JimiRuntime;
import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import io.leavesfly.jimi.adk.api.message.ContentPart;
import io.leavesfly.jimi.adk.api.message.TextPart;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.api.wire.WireMessage;
import io.leavesfly.jimi.adk.core.wire.messages.*;
import io.leavesfly.jimi.adk.llm.LLMFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Web 核心服务
 * 管理多会话、任务执行、流式输出
 * 复用 jimi-work 的 WorkService 核心逻辑
 */
@Service
public class WebWorkService {

    private static final Logger log = LoggerFactory.getLogger(WebWorkService.class);
    private static final String SESSIONS_FILE = "web-sessions.json";
    private static final int MAX_SESSION_HISTORY = 100;
    private static final String JIMI_DIR = ".jimi";

    /** LLM 实例 */
    private LLM llm;
    /** Jimi 配置 */
    private JimiConfig jimiConfig;
    /** 活跃会话 */
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();
    /** 持久化目录 */
    private Path dataDir;
    /** JSON 序列化 */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .registerModule(new JavaTimeModule());

    @Value("${jimi.work-dir:#{null}}")
    private String configWorkDir;

    @PostConstruct
    public void init() {
        // 加载配置
        Path workDir = configWorkDir != null ? Paths.get(configWorkDir) : Paths.get(System.getProperty("user.dir"));
        this.jimiConfig = JimiConfig.load(workDir);

        // 初始化 LLM
        LLMConfig llmConfig = jimiConfig.toLLMConfig();
        LLMFactory llmFactory = new LLMFactory();
        this.llm = llmFactory.create(llmConfig);
        log.info("LLM 已初始化: provider={}, model={}", llmConfig.getProvider(), llmConfig.getModel());

        // 初始化数据目录
        this.dataDir = Paths.get(System.getProperty("user.home"), JIMI_DIR);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.warn("创建数据目录失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭 WebWorkService...");
        for (String sessionId : sessions.keySet()) {
            try {
                cancelTask(sessionId);
            } catch (Exception e) {
                log.warn("取消任务失败: {}", sessionId, e);
            }
        }
        sessions.clear();
        log.info("WebWorkService 已关闭");
    }

    // ==================== 会话管理 ====================

    /**
     * 创建工作会话
     */
    public WebSession createSession(String workDirPath, String agentName) {
        Path workDir = workDirPath != null ? Paths.get(workDirPath)
                : Paths.get(System.getProperty("user.dir"));
        agentName = agentName != null ? agentName : "default";
        log.info("创建会话: workDir={}, agent={}", workDir, agentName);

        EngineBundle bundle = buildEngine(workDir, agentName);
        WebSession session = new WebSession(bundle.engine(), bundle.wire(), workDir, agentName);
        sessions.put(session.getId(), session);

        log.info("会话已创建: {}", session.getId());
        return session;
    }

    /**
     * 获取会话
     */
    public WebSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 获取所有活跃会话
     */
    public List<WebSession> getAllSessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * 关闭会话
     */
    public void closeSession(String sessionId) {
        WebSession session = sessions.remove(sessionId);
        if (session != null) {
            if (session.isRunning()) {
                session.getEngine().interrupt();
                session.endJob();
            }
            log.info("会话已关闭: {}", sessionId);
        }
    }

    /**
     * 获取可用的 Agent 列表
     */
    public List<String> getAvailableAgents() {
        return List.of("default", "code", "architect", "doc", "devops", "quality");
    }

    // ==================== 任务执行 ====================

    /**
     * 执行任务（流式输出 SSE）
     */
    public Flux<StreamEvent> execute(String sessionId, String input) {
        WebSession session = sessions.get(sessionId);
        if (session == null) {
            return Flux.error(new IllegalArgumentException("会话不存在: " + sessionId));
        }
        if (session.isRunning()) {
            return Flux.error(new IllegalStateException("会话正在运行中"));
        }

        Engine engine = session.getEngine();
        String jobId = UUID.randomUUID().toString();
        session.startJob(jobId);

        Sinks.Many<StreamEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        CountDownLatch subscriptionReady = new CountDownLatch(1);

        Thread execThread = new Thread(() -> {
            reactor.core.Disposable subscription = null;
            try {
                // 订阅 Wire 消息流
                subscription = session.getWire().asFlux()
                        .doOnSubscribe(s -> subscriptionReady.countDown())
                        .subscribe(msg -> {
                            StreamEvent event = convertWireToEvent(msg);
                            if (event != null) {
                                sink.tryEmitNext(event);
                            }
                        }, e -> {
                            log.error("Wire 错误", e);
                            sink.tryEmitNext(StreamEvent.error(e.getMessage()));
                        });

                subscriptionReady.await(1, TimeUnit.SECONDS);
                Thread.sleep(50);

                // 执行
                ExecutionResult result = engine.run(input).block();

                Thread.sleep(200);

                if (result != null && !result.isSuccess()) {
                    sink.tryEmitNext(StreamEvent.error(
                            result.getError() != null ? result.getError() : "执行失败"));
                }

                log.info("任务完成: jobId={}", jobId);
            } catch (Exception e) {
                log.error("任务异常: jobId={}", jobId, e);
                sink.tryEmitNext(StreamEvent.error(e.getMessage()));
            } finally {
                if (subscription != null) {
                    subscription.dispose();
                }
                session.endJob();
                sink.tryEmitNext(StreamEvent.done());
                sink.tryEmitComplete();
            }
        }, "jimi-web-exec-" + jobId);
        execThread.setDaemon(true);
        execThread.start();

        return sink.asFlux();
    }

    /**
     * 取消任务
     */
    public void cancelTask(String sessionId) {
        WebSession session = sessions.get(sessionId);
        if (session != null && session.isRunning()) {
            session.getEngine().interrupt();
            session.endJob();
            log.info("任务已取消: sessionId={}", sessionId);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 构建引擎
     */
    private EngineBundle buildEngine(Path workDir, String agentName) {
        // 构建 Agent
        Agent agent = Agent.builder()
                .name(agentName)
                .description("Jimi Web Agent")
                .version("2.0.0")
                .systemPrompt("你是 Jimi，一个强大的 AI 编程助手。\n请始终使用清晰、专业的语言，并在执行危险操作前确认。")
                .maxSteps(100)
                .build();

        // 使用 JimiRuntime 统一构建
        JimiRuntime runtime = JimiRuntime.builder()
                .agent(agent)
                .llm(llm)
                .workDir(workDir)
                .maxContextTokens(jimiConfig.getMaxContextTokens())
                .build();

        return new EngineBundle(runtime.getEngine(), runtime.getWire());
    }

    /**
     * Wire 消息转换为 StreamEvent
     */
    private StreamEvent convertWireToEvent(WireMessage msg) {
        if (msg instanceof ContentPartMessage cpm) {
            ContentPart part = cpm.getContentPart();
            if (part instanceof TextPart tp) {
                if (cpm.getContentType() == ContentPartMessage.ContentType.REASONING) {
                    return StreamEvent.reasoning(tp.getText());
                }
                return StreamEvent.text(tp.getText());
            }
        } else if (msg instanceof ToolCallMessage tcm) {
            String toolName = tcm.getToolCall().getFunction().getName();
            String toolCallId = tcm.getToolCall().getId();
            String args = tcm.getToolCall().getFunction().getArguments();
            return StreamEvent.toolCall(toolName, toolCallId, args);
        } else if (msg instanceof ToolResultMessage trm) {
            String content = trm.getToolResult() != null ? trm.getToolResult().getMessage() : "";
            return StreamEvent.toolResult(trm.getToolName(), content);
        } else if (msg instanceof StepBegin sb) {
            return StreamEvent.stepBegin(sb.getStepNumber());
        } else if (msg instanceof StepEnd se) {
            return StreamEvent.stepEnd(se.getStepNumber());
        }
        return null;
    }

    // ==================== 内部模型 ====================

    /**
     * Web 会话
     */
    @lombok.Getter
    public static class WebSession {
        private final String id;
        private final Engine engine;
        private final Wire wire;
        private final Path workDir;
        private final String agentName;
        private final LocalDateTime createdAt;
        private volatile String currentJobId;
        private volatile boolean running;

        public WebSession(Engine engine, Wire wire, Path workDir, String agentName) {
            this.id = UUID.randomUUID().toString();
            this.engine = engine;
            this.wire = wire;
            this.workDir = workDir;
            this.agentName = agentName;
            this.createdAt = LocalDateTime.now();
        }

        public void startJob(String jobId) {
            this.currentJobId = jobId;
            this.running = true;
        }

        public void endJob() {
            this.currentJobId = null;
            this.running = false;
        }

        public String getDisplayName() {
            String dirName = workDir.getFileName() != null
                    ? workDir.getFileName().toString() : workDir.toString();
            return dirName + " (" + agentName + ")";
        }
    }

    /**
     * SSE 事件
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class StreamEvent {
        public enum Type {
            TEXT, REASONING, TOOL_CALL, TOOL_RESULT,
            STEP_BEGIN, STEP_END, ERROR, DONE
        }

        private Type type;
        private String content;
        private String toolName;
        private String toolCallId;
        private String toolArgs;
        private int stepNumber;

        public static StreamEvent text(String text) {
            return StreamEvent.builder().type(Type.TEXT).content(text).build();
        }

        public static StreamEvent reasoning(String text) {
            return StreamEvent.builder().type(Type.REASONING).content(text).build();
        }

        public static StreamEvent toolCall(String name, String id, String args) {
            return StreamEvent.builder().type(Type.TOOL_CALL)
                    .toolName(name).toolCallId(id).toolArgs(args).build();
        }

        public static StreamEvent toolResult(String name, String content) {
            return StreamEvent.builder().type(Type.TOOL_RESULT)
                    .toolName(name).content(content).build();
        }

        public static StreamEvent stepBegin(int step) {
            return StreamEvent.builder().type(Type.STEP_BEGIN).stepNumber(step).build();
        }

        public static StreamEvent stepEnd(int step) {
            return StreamEvent.builder().type(Type.STEP_END).stepNumber(step).build();
        }

        public static StreamEvent error(String message) {
            return StreamEvent.builder().type(Type.ERROR).content(message).build();
        }

        public static StreamEvent done() {
            return StreamEvent.builder().type(Type.DONE).build();
        }
    }

    /** Engine + Wire 组合 */
    private record EngineBundle(Engine engine, Wire wire) {}
}
