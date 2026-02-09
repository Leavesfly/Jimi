package io.leavesfly.jimi.web.service;

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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.io.InputStream;
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
    private static final String CONFIG_FILE = "config.yaml";

    /** LLM 实例 */
    private LLM llm;
    /** 配置 */
    private Map<String, Object> config;
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
        config = loadConfig();

        // 初始化 LLM
        LLMConfig llmConfig = buildLLMConfig();
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

        Engine engine = buildEngine(workDir, agentName);
        WebSession session = new WebSession(engine, workDir, agentName);
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
                subscription = engine.getWire().asFlux()
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
    @SuppressWarnings("unchecked")
    private Engine buildEngine(Path workDir, String agentName) {
        Wire wire = new DefaultWire();
        Context context = new DefaultContext();
        ObjectMapper engineMapper = new ObjectMapper();
        ToolRegistry toolRegistry = new DefaultToolRegistry(engineMapper);

        // 加载 Agent 和工具
        AgentSpec agentSpec = AgentSpec.builder()
                .name(agentName)
                .description("Jimi Web Agent")
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
            toolDesc.append("- ").append(tool.getName()).append(": ")
                    .append(tool.getDescription()).append("\n");
        }

        Agent agent = Agent.builder()
                .name(agentName)
                .description("Jimi Web Agent")
                .version("2.0.0")
                .systemPrompt("你是 Jimi，一个强大的 AI 编程助手。\n"
                        + "你有以下工具可用：\n" + toolDesc
                        + "请始终使用清晰、专业的语言，并在执行危险操作前确认。")
                .tools(tools)
                .maxSteps(100)
                .build();

        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .workDir(workDir)
                .yoloMode(getBool("yoloMode", false))
                .maxContextTokens(getInt("maxContextTokens", 100000))
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

    // ==================== 配置加载 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() {
        Map<String, Object> merged = new LinkedHashMap<>();

        // 1. classpath 默认配置
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                Map<String, Object> data = new Yaml().load(is);
                if (data != null) deepMerge(merged, data);
            }
        } catch (Exception e) {
            log.debug("加载 classpath 默认配置失败: {}", e.getMessage());
        }

        // 2. ~/.jimi/config.yaml
        loadYamlFile(Paths.get(System.getProperty("user.home"), JIMI_DIR, CONFIG_FILE), merged);

        // 3. 工作目录 .jimi/config.yaml
        if (configWorkDir != null) {
            loadYamlFile(Paths.get(configWorkDir, JIMI_DIR, CONFIG_FILE), merged);
        }

        return merged;
    }

    @SuppressWarnings("unchecked")
    private void loadYamlFile(Path path, Map<String, Object> target) {
        if (!Files.exists(path)) return;
        try (InputStream is = Files.newInputStream(path)) {
            Map<String, Object> data = new Yaml().load(is);
            if (data != null) {
                deepMerge(target, data);
                log.info("已加载配置: {}", path);
            }
        } catch (Exception e) {
            log.warn("读取配置失败: {} - {}", path, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (var entry : source.entrySet()) {
            Object sv = entry.getValue(), tv = target.get(entry.getKey());
            if (sv instanceof Map && tv instanceof Map) {
                deepMerge((Map<String, Object>) tv, (Map<String, Object>) sv);
            } else {
                target.put(entry.getKey(), sv);
            }
        }
    }

    private LLMConfig buildLLMConfig() {
        Map<String, Object> llmMap = getMap("llm");
        String provider = str(llmMap, "provider", "openai");
        String model = str(llmMap, "model", "gpt-4o");
        String baseUrl = str(llmMap, "baseUrl", null);
        double temperature = dbl(llmMap, "temperature", 0.7);
        int maxTokens = num(llmMap, "maxTokens", 4096);
        int connectTimeout = num(llmMap, "connectTimeout", 30);
        int readTimeout = num(llmMap, "readTimeout", 120);
        String apiKey = resolveApiKey(provider, str(llmMap, "apiKey", null));

        return LLMConfig.builder()
                .provider(provider).model(model).apiKey(apiKey).baseUrl(baseUrl)
                .temperature(temperature).maxTokens(maxTokens)
                .connectTimeout(connectTimeout).readTimeout(readTimeout)
                .build();
    }

    private String resolveApiKey(String provider, String configKey) {
        String key = System.getenv("JIMI_API_KEY");
        if (key != null && !key.isEmpty()) return key;

        String envName = provider.toUpperCase().replace("-", "_") + "_API_KEY";
        key = System.getenv(envName);
        if (key != null && !key.isEmpty()) return key;

        if (!"openai".equalsIgnoreCase(provider)) {
            key = System.getenv("OPENAI_API_KEY");
            if (key != null && !key.isEmpty()) return key;
        }

        if (configKey != null && configKey.startsWith("${") && configKey.endsWith("}")) {
            key = System.getenv(configKey.substring(2, configKey.length() - 1));
            if (key != null && !key.isEmpty()) return key;
        }

        return configKey;
    }

    // ---- config helpers ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(String key) {
        Object v = config.get(key);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    private boolean getBool(String key, boolean def) {
        Object v = config.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return def;
    }

    private int getInt(String key, int def) {
        Object v = config.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }

    private static String str(Map<String, Object> m, String k, String def) {
        if (m == null) return def;
        Object v = m.get(k);
        return v != null ? v.toString() : def;
    }

    private static double dbl(Map<String, Object> m, String k, double def) {
        if (m == null) return def;
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return def;
    }

    private static int num(Map<String, Object> m, String k, int def) {
        if (m == null) return def;
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }

    // ==================== 内部模型 ====================

    /**
     * Web 会话
     */
    @lombok.Getter
    public static class WebSession {
        private final String id;
        private final Engine engine;
        private final Path workDir;
        private final String agentName;
        private final LocalDateTime createdAt;
        private volatile String currentJobId;
        private volatile boolean running;

        public WebSession(Engine engine, Path workDir, String agentName) {
            this.id = UUID.randomUUID().toString();
            this.engine = engine;
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
}
