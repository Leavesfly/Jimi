package io.leavesfly.jimi.core.engine.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.core.agent.Agent;
import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.core.JimiEngine;
import io.leavesfly.jimi.core.compaction.SimpleCompaction;
import io.leavesfly.jimi.core.engine.runtime.Runtime;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.tool.Tool;
import io.leavesfly.jimi.tool.ToolProvider;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolRegistryFactory;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.WireImpl;
import io.leavesfly.jimi.wire.message.AsyncSubagentCompleted;
import io.leavesfly.jimi.wire.message.AsyncSubagentProgress;
import io.leavesfly.jimi.wire.message.AsyncSubagentStarted;
import io.leavesfly.jimi.wire.message.AsyncSubagentTrigger;
import io.leavesfly.jimi.wire.message.StepBegin;
import jakarta.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 异步子代理管理器
 * 负责异步子代理的生命周期管理，包括启动、监控、取消和清理
 *
 * @author Jimi
 */
@Slf4j
@Service
public class AsyncSubagentManager {

    /**
     * 活跃的异步子代理映射
     */
    private final ConcurrentHashMap<String, AsyncSubagent> activeSubagents = new ConcurrentHashMap<>();

    /**
     * 已完成的子代理缓存（保留最近N个）
     */
    private final LinkedHashMap<String, AsyncSubagent> completedSubagents = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AsyncSubagent> eldest) {
            return size() > MAX_COMPLETED_CACHE;
        }
    };

    private static final int MAX_COMPLETED_CACHE = 50;

    /**
     * 后台执行线程池
     */
    private final Scheduler asyncScheduler = Schedulers.newBoundedElastic(
            10,                    // 最大线程数
            100,                   // 最大排队任务
            "async-subagent"       // 线程名前缀
    );

    /**
     * 步骤计数器（用于进度跟踪）
     */
    private final AtomicInteger stepCounter = new AtomicInteger(0);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ToolRegistryFactory toolRegistryFactory;

    @Autowired
    private List<ToolProvider> toolProviders;

    @Autowired
    private AsyncSubagentPersistence persistence;

    /**
     * 启动 Watch 模式子代理（持续监控）
     *
     * @param agent                要执行的 Agent
     * @param runtime              运行时上下文
     * @param prompt               任务提示词
     * @param watchTarget          监控目标（文件路径或命令）
     * @param triggerPattern       触发模式（正则表达式）
     * @param onTrigger            触发后的动作描述
     * @param continueAfterTrigger 触发后是否继续监控
     * @param parentWire           父 Wire
     * @param timeout              超时时间
     * @return 子代理 ID
     */
    public Mono<String> startWatcher(
            Agent agent,
            Runtime runtime,
            String prompt,
            String watchTarget,
            String triggerPattern,
            @Nullable String onTrigger,
            boolean continueAfterTrigger,
            @Nullable Wire parentWire,
            @Nullable Duration timeout) {

        return Mono.defer(() -> {
            String subagentId = generateSubagentId();

            log.info("Starting watch subagent: {} [{}] target={}", agent.getName(), subagentId, watchTarget);

            // 构建监控任务提示词
            String watchPrompt = buildWatchPrompt(prompt, watchTarget, triggerPattern, onTrigger, continueAfterTrigger);

            // 创建异步子代理实体
            AsyncSubagent asyncSubagent = AsyncSubagent.builder()
                    .id(subagentId)
                    .name(agent.getName() + " (Watch)")
                    .mode(AsyncSubagentMode.WATCH)
                    .status(AsyncSubagentStatus.PENDING)
                    .agent(agent)
                    .prompt(watchPrompt)
                    .startTime(Instant.now())
                    .timeout(timeout)
                    .triggerPattern(triggerPattern)
                    .workDir(runtime.getSession().getWorkDir())
                    .build();

            // 注册到活跃列表
            activeSubagents.put(subagentId, asyncSubagent);

            // 后台启动执行
            Disposable subscription = executeWatchInBackground(
                    asyncSubagent,
                    runtime,
                    watchPrompt,
                    watchTarget,
                    triggerPattern,
                    continueAfterTrigger,
                    parentWire)
                    .subscribeOn(asyncScheduler)
                    .subscribe(
                            v -> {
                            },
                            error -> log.error("Watch subagent {} execution error", subagentId, error)
                    );

            asyncSubagent.setSubscription(subscription);
            asyncSubagent.setStatus(AsyncSubagentStatus.RUNNING);

            // 发送启动消息
            if (parentWire != null) {
                parentWire.send(new AsyncSubagentStarted(
                        subagentId,
                        asyncSubagent.getName(),
                        AsyncSubagentMode.WATCH.getValue(),
                        Instant.now()
                ));
            }

            log.info("Watch subagent started: {} [{}]", agent.getName(), subagentId);

            return Mono.just(subagentId);
        });
    }

    /**
     * 构建 Watch 任务提示词
     */
    private String buildWatchPrompt(String basePrompt, String watchTarget, String triggerPattern,
                                    @Nullable String onTrigger, boolean continueAfterTrigger) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个监控代理，任务如下：\n\n");

        if (basePrompt != null && !basePrompt.isBlank()) {
            sb.append("任务背景：").append(basePrompt).append("\n\n");
        }

        sb.append("监控配置：\n");
        sb.append("- 监控目标: ").append(watchTarget).append("\n");
        sb.append("- 触发模式: ").append(triggerPattern).append("\n");

        if (onTrigger != null && !onTrigger.isBlank()) {
            sb.append("- 触发后动作: ").append(onTrigger).append("\n");
        }

        sb.append("\n执行指南：\n");
        sb.append("1. 使用 Shell 工具执行监控命令（如 tail -f 或 直接读取文件）\n");
        sb.append("2. 分析输出内容，使用正则表达式 `").append(triggerPattern).append("` 进行匹配\n");
        sb.append("3. 匹配到时，向用户报告触发事件\n");

        if (continueAfterTrigger) {
            sb.append("4. 触发后继续监控\n");
        } else {
            sb.append("4. 触发后停止监控并汇报结果\n");
        }

        return sb.toString();
    }

    /**
     * Watch 模式后台执行
     */
    private Mono<Void> executeWatchInBackground(
            AsyncSubagent asyncSubagent,
            Runtime runtime,
            String prompt,
            String watchTarget,
            String triggerPattern,
            boolean continueAfterTrigger,
            @Nullable Wire parentWire
    ) {
        return Mono.defer(() -> {
            try {
                // 创建独立上下文
                Path historyFile = createAsyncHistoryFile(asyncSubagent.getId());
                Context context = new Context(historyFile, objectMapper);
                asyncSubagent.setContext(context);

                // 创建工具注册表
                ToolRegistry toolRegistry = createToolRegistry(asyncSubagent.getAgent(), runtime);

                // 创建独立 Wire
                Wire subWire = new WireImpl();

                // 创建引擎（使用 Builder 模式）
                JimiEngine engine = JimiEngine.builder()
                        .agent(asyncSubagent.getAgent())
                        .runtime(runtime)
                        .context(context)
                        .toolRegistry(toolRegistry)
                        .wire(subWire)
                        .compaction(new SimpleCompaction())
                        .isSubagent(true)
                        .build();
                asyncSubagent.setEngine(engine);

                // 编译正则表达式
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(triggerPattern);

                // 监听子 Wire，检测触发模式
                if (parentWire != null) {
                    subWire.asFlux()
                            .filter(msg -> msg instanceof StepBegin)
                            .subscribe(msg -> {
                                // 检查上下文中的最新输出是否匹配触发模式
                                String lastOutput = extractLastToolOutput(context);
                                if (lastOutput != null && pattern.matcher(lastOutput).find()) {
                                    // 找到匹配行
                                    String matchedLine = findMatchedLine(lastOutput, pattern);

                                    log.info("Watch trigger matched for {}: {}", asyncSubagent.getId(), matchedLine);

                                    // 发送触发消息
                                    parentWire.send(new AsyncSubagentTrigger(
                                            asyncSubagent.getId(),
                                            triggerPattern,
                                            matchedLine,
                                            Instant.now()
                                    ));

                                    if (!continueAfterTrigger) {
                                        // 触发后停止：取消执行
                                        cancel(asyncSubagent.getId());
                                    }
                                }
                            });
                }

                // 执行（带超时）
                Mono<Void> execution = engine.run(prompt);
                if (asyncSubagent.getTimeout() != null) {
                    execution = execution.timeout(asyncSubagent.getTimeout());
                }

                return execution
                        .doOnSuccess(v -> handleComplete(asyncSubagent, parentWire))
                        .doOnError(e -> handleError(asyncSubagent, e, parentWire))
                        .onErrorComplete();

            } catch (Exception e) {
                handleError(asyncSubagent, e, parentWire);
                return Mono.empty();
            }
        });
    }

    /**
     * 提取最后一次工具输出
     */
    private String extractLastToolOutput(Context context) {
        if (context == null) {
            return null;
        }

        List<Message> history = context.getHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg.getRole() == MessageRole.TOOL) {
                return msg.getContentParts().stream()
                        .filter(part -> part instanceof TextPart)
                        .map(part -> ((TextPart) part).getText())
                        .filter(text -> text != null && !text.isEmpty())
                        .collect(Collectors.joining("\n"));
            }
        }
        return null;
    }

    /**
     * 查找匹配行
     */
    private String findMatchedLine(String content, java.util.regex.Pattern pattern) {
        if (content == null) {
            return null;
        }

        String[] lines = content.split("\n");
        for (String line : lines) {
            if (pattern.matcher(line).find()) {
                return line.length() > 200 ? line.substring(0, 200) + "..." : line;
            }
        }
        return "[Pattern matched in content]";
    }

    /**
     * 启动异步子代理（Fire-and-Forget 模式）
     *
     * @param agent      要执行的 Agent
     * @param runtime    运行时上下文
     * @param prompt     任务提示词
     * @param parentWire 父 Wire（用于发送消息，可选）
     * @param callback   完成回调（可选）
     * @param timeout    超时时间（可选）
     * @return 子代理 ID
     */
    public Mono<String> startAsync(
            Agent agent,
            Runtime runtime,
            String prompt,
            @Nullable Wire parentWire,
            @Nullable AsyncSubagentCallback callback,
            @Nullable Duration timeout
    ) {
        return Mono.defer(() -> {
            String subagentId = generateSubagentId();

            log.info("Starting async subagent: {} [{}]", agent.getName(), subagentId);

            // 创建异步子代理实体
            AsyncSubagent asyncSubagent = AsyncSubagent.builder()
                    .id(subagentId)
                    .name(agent.getName())
                    .mode(AsyncSubagentMode.FIRE_AND_FORGET)
                    .status(AsyncSubagentStatus.PENDING)
                    .agent(agent)
                    .prompt(prompt)
                    .startTime(Instant.now())
                    .callback(callback)
                    .timeout(timeout)
                    .workDir(runtime.getSession().getWorkDir())
                    .build();

            // 注册到活跃列表
            activeSubagents.put(subagentId, asyncSubagent);

            // 后台启动执行
            Disposable subscription = executeInBackground(asyncSubagent, runtime, prompt, parentWire)
                    .subscribeOn(asyncScheduler)
                    .subscribe(
                            v -> {
                            },
                            error -> log.error("Async subagent {} execution error", subagentId, error)
                    );

            asyncSubagent.setSubscription(subscription);
            asyncSubagent.setStatus(AsyncSubagentStatus.RUNNING);

            // 发送启动消息
            if (parentWire != null) {
                parentWire.send(new AsyncSubagentStarted(
                        subagentId,
                        agent.getName(),
                        AsyncSubagentMode.FIRE_AND_FORGET.getValue(),
                        Instant.now()
                ));
            }

            log.info("Async subagent started: {} [{}]", agent.getName(), subagentId);

            return Mono.just(subagentId);
        });
    }

    /**
     * 后台执行子代理
     */
    private Mono<Void> executeInBackground(
            AsyncSubagent asyncSubagent,
            Runtime runtime,
            String prompt,
            @Nullable Wire parentWire
    ) {
        return Mono.defer(() -> {
            try {
                // 创建独立上下文
                Path historyFile = createAsyncHistoryFile(asyncSubagent.getId());
                Context context = new Context(historyFile, objectMapper);
                asyncSubagent.setContext(context);

                // 创建工具注册表
                ToolRegistry toolRegistry = createToolRegistry(asyncSubagent.getAgent(), runtime);

//                // 创建独立 Wire
                Wire subWire = new WireImpl();

                // 创建引擎（使用 Builder 模式）
                JimiEngine engine = JimiEngine.builder()
                        .agent(asyncSubagent.getAgent())
                        .runtime(runtime)
                        .context(context)
                        .toolRegistry(toolRegistry)
                        .wire(subWire)
                        .compaction(new SimpleCompaction())
                        .isSubagent(true)
                        .build();
                asyncSubagent.setEngine(engine);

                // Wire 事件转发（仅转发关键事件）
                if (parentWire != null) {
                    subWire.asFlux()
                            .filter(msg -> msg instanceof StepBegin)
                            .subscribe(msg -> {
                                int step = stepCounter.incrementAndGet();
                                parentWire.send(new AsyncSubagentProgress(
                                        asyncSubagent.getId(),
                                        "Step " + step,
                                        step
                                ));
                            });
                }

                // 执行（带超时）
                Mono<Void> execution = engine.run(prompt);
                if (asyncSubagent.getTimeout() != null) {
                    execution = execution.timeout(asyncSubagent.getTimeout());
                }

                return execution
                        .doOnSuccess(v -> handleComplete(asyncSubagent, parentWire))
                        .doOnError(e -> handleError(asyncSubagent, e, parentWire))
                        .onErrorComplete();  // 防止错误传播

            } catch (Exception e) {
                handleError(asyncSubagent, e, parentWire);
                return Mono.empty();
            }
        });
    }

    /**
     * 处理完成
     */
    private void handleComplete(AsyncSubagent asyncSubagent, @Nullable Wire parentWire) {
        asyncSubagent.setStatus(AsyncSubagentStatus.COMPLETED);
        asyncSubagent.setEndTime(Instant.now());

        // 提取结果
        String result = extractResult(asyncSubagent.getContext());
        asyncSubagent.setResult(result);

        // 移动到已完成列表
        activeSubagents.remove(asyncSubagent.getId());
        synchronized (completedSubagents) {
            completedSubagents.put(asyncSubagent.getId(), asyncSubagent);
        }

        // 发送完成消息
        if (parentWire != null) {
            Duration duration = asyncSubagent.getRunningDuration();
            parentWire.send(new AsyncSubagentCompleted(
                    asyncSubagent.getId(),
                    result,
                    true,
                    duration
            ));
        }

        // 执行回调
        if (asyncSubagent.getCallback() != null) {
            try {
                asyncSubagent.getCallback().onComplete(asyncSubagent);
            } catch (Exception e) {
                log.error("Callback error for subagent {}", asyncSubagent.getId(), e);
            }
        }

        log.info("Async subagent {} completed in {}s",
                asyncSubagent.getId(),
                asyncSubagent.getRunningDuration().getSeconds());

        // 持久化记录
        if (asyncSubagent.getWorkDir() != null) {
            persistence.save(asyncSubagent.getWorkDir(), asyncSubagent);
        }
    }

    /**
     * 处理错误
     */
    private void handleError(AsyncSubagent asyncSubagent, Throwable error, @Nullable Wire parentWire) {
        if (error instanceof TimeoutException) {
            asyncSubagent.setStatus(AsyncSubagentStatus.TIMEOUT);
            log.warn("Async subagent {} timed out", asyncSubagent.getId());
        } else {
            asyncSubagent.setStatus(AsyncSubagentStatus.FAILED);
            log.error("Async subagent {} failed", asyncSubagent.getId(), error);
        }
        asyncSubagent.setEndTime(Instant.now());
        asyncSubagent.setError(error);

        activeSubagents.remove(asyncSubagent.getId());
        synchronized (completedSubagents) {
            completedSubagents.put(asyncSubagent.getId(), asyncSubagent);
        }

        if (parentWire != null) {
            Duration duration = asyncSubagent.getRunningDuration();
            parentWire.send(new AsyncSubagentCompleted(
                    asyncSubagent.getId(),
                    error.getMessage(),
                    false,
                    duration
            ));
        }

        // 执行回调
        if (asyncSubagent.getCallback() != null) {
            try {
                asyncSubagent.getCallback().onComplete(asyncSubagent);
            } catch (Exception e) {
                log.error("Callback error for subagent {}", asyncSubagent.getId(), e);
            }
        }

        // 持久化记录
        if (asyncSubagent.getWorkDir() != null) {
            persistence.save(asyncSubagent.getWorkDir(), asyncSubagent);
        }
    }

    /**
     * 查询子代理
     */
    public Optional<AsyncSubagent> getSubagent(String id) {
        AsyncSubagent active = activeSubagents.get(id);
        if (active != null) {
            return Optional.of(active);
        }
        synchronized (completedSubagents) {
            return Optional.ofNullable(completedSubagents.get(id));
        }
    }

    /**
     * 列出所有活跃子代理
     */
    public List<AsyncSubagent> listActive() {
        return new ArrayList<>(activeSubagents.values());
    }

    /**
     * 列出最近完成的子代理
     */
    public List<AsyncSubagent> listCompleted() {
        synchronized (completedSubagents) {
            return new ArrayList<>(completedSubagents.values());
        }
    }

    /**
     * 取消子代理
     */
    public boolean cancel(String id) {
        AsyncSubagent subagent = activeSubagents.get(id);
        if (subagent != null && subagent.getSubscription() != null) {
            subagent.getSubscription().dispose();
            subagent.setStatus(AsyncSubagentStatus.CANCELLED);
            subagent.setEndTime(Instant.now());
            activeSubagents.remove(id);
            synchronized (completedSubagents) {
                completedSubagents.put(id, subagent);
            }
            log.info("Async subagent {} cancelled", id);
            return true;
        }
        return false;
    }

    /**
     * 获取活跃子代理数量
     */
    public int getActiveCount() {
        return activeSubagents.size();
    }

    /**
     * 优雅关闭所有子代理
     */
    @PreDestroy
    public void shutdownAll() {
        log.info("Shutting down {} async subagents", activeSubagents.size());
        activeSubagents.values().forEach(subagent -> {
            if (subagent.getSubscription() != null && !subagent.getSubscription().isDisposed()) {
                subagent.getSubscription().dispose();
            }
        });
        activeSubagents.clear();
        asyncScheduler.dispose();
        log.info("Async subagent manager shutdown complete");
    }

    // ==================== 辅助方法 ====================

    /**
     * 生成子代理 ID（短 UUID）
     */
    private String generateSubagentId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 创建异步历史文件
     */
    private Path createAsyncHistoryFile(String subagentId) throws IOException {
        Path tempDir = Files.createTempDirectory("jimi-async");
        Path historyFile = tempDir.resolve("history_" + subagentId + ".json");
        Files.createFile(historyFile);
        return historyFile;
    }

    /**
     * 创建工具注册表
     */
    private ToolRegistry createToolRegistry(Agent agent, Runtime runtime) {
        AgentSpec agentSpec = AgentSpec.builder()
                .name(agent.getName())
                .build();
        ToolRegistry registry = toolRegistryFactory.create(
                runtime.getBuiltinArgs(),
                runtime.getApproval(),
                agentSpec,
                runtime, null
        );
        return registry;
    }

    /**
     * 从上下文提取最终结果
     */
    private String extractResult(Context context) {
        if (context == null) {
            return "(No context)";
        }

        List<Message> history = context.getHistory();
        if (history.isEmpty()) {
            return "(No history)";
        }

        // 获取最后一条助手消息
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg.getRole() == MessageRole.ASSISTANT) {
                return msg.getContentParts().stream()
                        .filter(part -> part instanceof TextPart)
                        .map(part -> ((TextPart) part).getText())
                        .filter(text -> text != null && !text.isEmpty())
                        .collect(Collectors.joining("\n"));
            }
        }

        return "(No assistant response)";
    }
}
