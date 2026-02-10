package io.leavesfly.jimi.adk.core.engine.async;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.api.message.Role;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.core.context.DefaultContext;
import io.leavesfly.jimi.adk.core.engine.DefaultEngine;
import io.leavesfly.jimi.adk.core.wire.DefaultWire;
import io.leavesfly.jimi.adk.core.wire.messages.StepBegin;
import io.leavesfly.jimi.adk.core.wire.messages.SubagentCompleted;
import io.leavesfly.jimi.adk.core.wire.messages.SubagentStarting;
import io.leavesfly.jimi.adk.core.wire.messages.StatusUpdate;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 异步子代理管理器
 * 负责异步子代理的生命周期管理，包括启动、监控、取消和清理
 */
@Slf4j
public class AsyncSubagentManager {

    /** 活跃的异步子代理映射 */
    private final ConcurrentHashMap<String, AsyncSubagent> activeSubagents = new ConcurrentHashMap<>();

    /** 已完成的子代理缓存（保留最近 N 个） */
    private final LinkedHashMap<String, AsyncSubagent> completedSubagents = new LinkedHashMap<>();

    private static final int MAX_COMPLETED_CACHE = 50;

    /** 后台执行线程池 */
    private final Scheduler asyncScheduler = Schedulers.newBoundedElastic(
            10, 100, "async-subagent"
    );

    /** 步骤计数器 */
    private final AtomicInteger stepCounter = new AtomicInteger(0);

    private final AsyncSubagentPersistence persistence;
    private final ToolRegistry toolRegistry;

    public AsyncSubagentManager(ToolRegistry toolRegistry, AsyncSubagentPersistence persistence) {
        this.toolRegistry = toolRegistry;
        this.persistence = persistence;
    }

    public AsyncSubagentManager(ToolRegistry toolRegistry) {
        this(toolRegistry, new AsyncSubagentPersistence());
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
            Wire parentWire,
            AsyncSubagentCallback callback,
            Duration timeout
    ) {
        return Mono.defer(() -> {
            String subagentId = generateSubagentId();

            log.info("Starting async subagent: {} [{}]", agent.getName(), subagentId);

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
                    .workDir(runtime.getConfig().getWorkDir())
                    .build();

            activeSubagents.put(subagentId, asyncSubagent);

            Disposable subscription = executeInBackground(asyncSubagent, runtime, prompt, parentWire)
                    .subscribeOn(asyncScheduler)
                    .subscribe(
                            v -> {},
                            error -> log.error("Async subagent {} execution error", subagentId, error)
                    );

            asyncSubagent.setSubscription(subscription);
            asyncSubagent.setStatus(AsyncSubagentStatus.RUNNING);

            if (parentWire != null) {
                parentWire.send(new SubagentStarting(agent.getName(), prompt));
            }

            log.info("Async subagent started: {} [{}]", agent.getName(), subagentId);

            return Mono.just(subagentId);
        });
    }

    /**
     * 启动 Watch 模式子代理（持续监控）
     */
    public Mono<String> startWatcher(
            Agent agent,
            Runtime runtime,
            String prompt,
            String watchTarget,
            String triggerPattern,
            String onTrigger,
            boolean continueAfterTrigger,
            Wire parentWire,
            Duration timeout
    ) {
        return Mono.defer(() -> {
            String subagentId = generateSubagentId();

            log.info("Starting watch subagent: {} [{}] target={}", agent.getName(), subagentId, watchTarget);

            String watchPrompt = buildWatchPrompt(prompt, watchTarget, triggerPattern,
                    onTrigger, continueAfterTrigger);

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
                    .workDir(runtime.getConfig().getWorkDir())
                    .build();

            activeSubagents.put(subagentId, asyncSubagent);

            Disposable subscription = executeWatchInBackground(
                    asyncSubagent, runtime, watchPrompt, watchTarget,
                    triggerPattern, continueAfterTrigger, parentWire)
                    .subscribeOn(asyncScheduler)
                    .subscribe(
                            v -> {},
                            error -> log.error("Watch subagent {} execution error", subagentId, error)
                    );

            asyncSubagent.setSubscription(subscription);
            asyncSubagent.setStatus(AsyncSubagentStatus.RUNNING);

            if (parentWire != null) {
                parentWire.send(new SubagentStarting(
                        asyncSubagent.getName(), watchPrompt));
            }

            log.info("Watch subagent started: {} [{}]", agent.getName(), subagentId);
            return Mono.just(subagentId);
        });
    }

    // ==================== 后台执行 ====================

    private Mono<Void> executeInBackground(
            AsyncSubagent asyncSubagent,
            Runtime runtime,
            String prompt,
            Wire parentWire
    ) {
        return Mono.defer(() -> {
            try {
                Context context = new DefaultContext();
                asyncSubagent.setContext(context);

                Wire subWire = new DefaultWire();

                Engine engine = DefaultEngine.builder()
                        .agent(asyncSubagent.getAgent())
                        .runtime(runtime)
                        .context(context)
                        .toolRegistry(toolRegistry)
                        .wire(subWire)
                        .build();

                // Wire 事件转发
                if (parentWire != null) {
                    subWire.ofType(StepBegin.class)
                            .subscribe(msg -> {
                                int step = stepCounter.incrementAndGet();
                                parentWire.send(StatusUpdate.info(
                                    "Async subagent " + asyncSubagent.getName() + " - Step " + step));
                            });
                }

                Mono<ExecutionResult> execution = engine.run(prompt);
                if (asyncSubagent.getTimeout() != null) {
                    execution = execution.timeout(asyncSubagent.getTimeout());
                }

                return execution
                        .doOnSuccess(result -> handleComplete(asyncSubagent, parentWire))
                        .doOnError(e -> handleError(asyncSubagent, e, parentWire))
                        .onErrorComplete()
                        .then();

            } catch (Exception e) {
                handleError(asyncSubagent, e, parentWire);
                return Mono.empty();
            }
        });
    }

    private Mono<Void> executeWatchInBackground(
            AsyncSubagent asyncSubagent,
            Runtime runtime,
            String prompt,
            String watchTarget,
            String triggerPattern,
            boolean continueAfterTrigger,
            Wire parentWire
    ) {
        return Mono.defer(() -> {
            try {
                Context context = new DefaultContext();
                asyncSubagent.setContext(context);

                Wire subWire = new DefaultWire();

                Engine engine = DefaultEngine.builder()
                        .agent(asyncSubagent.getAgent())
                        .runtime(runtime)
                        .context(context)
                        .toolRegistry(toolRegistry)
                        .wire(subWire)
                        .build();

                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(triggerPattern);

                if (parentWire != null) {
                    subWire.ofType(StepBegin.class)
                            .subscribe(msg -> {
                                String lastOutput = extractLastToolOutput(context);
                                if (lastOutput != null && pattern.matcher(lastOutput).find()) {
                                    String matchedLine = findMatchedLine(lastOutput, pattern);
                                    log.info("Watch trigger matched for {}: {}",
                                            asyncSubagent.getId(), matchedLine);

                                    parentWire.send(StatusUpdate.info(
                                        "Watch trigger matched: " + matchedLine));

                                    if (!continueAfterTrigger) {
                                        cancel(asyncSubagent.getId());
                                    }
                                }
                            });
                }

                Mono<ExecutionResult> execution = engine.run(prompt);
                if (asyncSubagent.getTimeout() != null) {
                    execution = execution.timeout(asyncSubagent.getTimeout());
                }

                return execution
                        .doOnSuccess(v -> handleComplete(asyncSubagent, parentWire))
                        .doOnError(e -> handleError(asyncSubagent, e, parentWire))
                        .onErrorComplete()
                        .then();

            } catch (Exception e) {
                handleError(asyncSubagent, e, parentWire);
                return Mono.empty();
            }
        });
    }

    // ==================== 生命周期管理 ====================

    private void handleComplete(AsyncSubagent asyncSubagent, Wire parentWire) {
        asyncSubagent.setStatus(AsyncSubagentStatus.COMPLETED);
        asyncSubagent.setEndTime(Instant.now());

        String result = extractResult(asyncSubagent.getContext());
        asyncSubagent.setResult(result);

        activeSubagents.remove(asyncSubagent.getId());
        addToCompleted(asyncSubagent);

        if (parentWire != null) {
            parentWire.send(SubagentCompleted.success(
                    asyncSubagent.getName(), result, 0, 0));
        }

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

        if (asyncSubagent.getWorkDir() != null && persistence != null) {
            persistence.save(asyncSubagent.getWorkDir(), asyncSubagent);
        }
    }

    private void handleError(AsyncSubagent asyncSubagent, Throwable error, Wire parentWire) {
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
        addToCompleted(asyncSubagent);

        if (parentWire != null) {
            parentWire.send(SubagentCompleted.failure(
                    asyncSubagent.getName(), error.getMessage()));
        }

        if (asyncSubagent.getCallback() != null) {
            try {
                asyncSubagent.getCallback().onComplete(asyncSubagent);
            } catch (Exception e) {
                log.error("Callback error for subagent {}", asyncSubagent.getId(), e);
            }
        }

        if (asyncSubagent.getWorkDir() != null && persistence != null) {
            persistence.save(asyncSubagent.getWorkDir(), asyncSubagent);
        }
    }

    // ==================== 查询与管理 ====================

    public Optional<AsyncSubagent> getSubagent(String id) {
        AsyncSubagent active = activeSubagents.get(id);
        if (active != null) {
            return Optional.of(active);
        }
        synchronized (completedSubagents) {
            return Optional.ofNullable(completedSubagents.get(id));
        }
    }

    public List<AsyncSubagent> listActive() {
        return new ArrayList<>(activeSubagents.values());
    }

    public List<AsyncSubagent> listCompleted() {
        synchronized (completedSubagents) {
            return new ArrayList<>(completedSubagents.values());
        }
    }

    public boolean cancel(String id) {
        AsyncSubagent subagent = activeSubagents.get(id);
        if (subagent != null && subagent.getSubscription() != null) {
            subagent.getSubscription().dispose();
            subagent.setStatus(AsyncSubagentStatus.CANCELLED);
            subagent.setEndTime(Instant.now());
            activeSubagents.remove(id);
            addToCompleted(subagent);
            log.info("Async subagent {} cancelled", id);
            return true;
        }
        return false;
    }

    public int getActiveCount() {
        return activeSubagents.size();
    }

    /**
     * 优雅关闭所有子代理
     */
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

    private String generateSubagentId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 添加到已完成缓存（限制大小）
     */
    private void addToCompleted(AsyncSubagent subagent) {
        synchronized (completedSubagents) {
            completedSubagents.put(subagent.getId(), subagent);
            while (completedSubagents.size() > MAX_COMPLETED_CACHE) {
                Iterator<String> it = completedSubagents.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }
    }

    private String buildWatchPrompt(String basePrompt, String watchTarget, String triggerPattern,
                                    String onTrigger, boolean continueAfterTrigger) {
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
        sb.append("1. 使用 Shell 工具执行监控命令\n");
        sb.append("2. 分析输出内容，使用正则表达式 `").append(triggerPattern).append("` 进行匹配\n");
        sb.append("3. 匹配到时，向用户报告触发事件\n");

        if (continueAfterTrigger) {
            sb.append("4. 触发后继续监控\n");
        } else {
            sb.append("4. 触发后停止监控并汇报结果\n");
        }

        return sb.toString();
    }

    private String extractLastToolOutput(Context context) {
        if (context == null) {
            return null;
        }
        List<Message> history = context.getHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg.getRole() == Role.TOOL) {
                return msg.getContent();
            }
        }
        return null;
    }

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

    private String extractResult(Context context) {
        if (context == null) {
            return "(No context)";
        }
        List<Message> history = context.getHistory();
        if (history.isEmpty()) {
            return "(No history)";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg.getRole() == Role.ASSISTANT) {
                return msg.getContent() != null ? msg.getContent() : "(No content)";
            }
        }
        return "(No assistant response)";
    }
}
