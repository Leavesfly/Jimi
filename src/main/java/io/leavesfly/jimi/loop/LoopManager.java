package io.leavesfly.jimi.loop;

import io.leavesfly.jimi.client.EngineClient;
import io.leavesfly.jimi.config.info.LoopEngineeringConfig;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 循环调度核心管理器
 * <p>
 * 管理 /loop 命令的生命周期：启动、暂停、恢复、停止。
 * 支持最多 maxConcurrentLoops 个循环并发运行，每个循环由独立的
 * 单线程调度器驱动，使用 scheduleWithFixedDelay 避免单次执行
 * 超过间隔时任务堆积。
 * <p>
 * 防失控保护（与 /goal 对齐）：
 * - loopMaxIterations：最大迭代次数，达到后自动停止
 * - loopTimeoutMinutes：超时自动停止
 * - loopMaxConsecutiveFailures：连续失败熔断自动停止
 */
@Slf4j
@Service
public class LoopManager {

    @Autowired
    private LoopEngineeringConfig config;

    /**
     * 活跃循环表：loopId -> LoopTask
     */
    private final Map<String, LoopTask> loops = new ConcurrentHashMap<>();
    private final AtomicInteger idSequence = new AtomicInteger(0);
    private final AtomicReference<String> lastStartedId = new AtomicReference<>();

    /**
     * 启动定时循环
     *
     * @param interval 循环间隔
     * @param prompt   每次循环执行的 prompt
     * @param client   EngineClient 实例
     * @return 新循环的 ID
     * @throws IllegalStateException 如果已达最大并发循环数
     */
    public synchronized String startLoop(Duration interval, String prompt, EngineClient client) {
        if (loops.size() >= config.getMaxConcurrentLoops()) {
            throw new IllegalStateException(
                    "已达最大并发循环数 " + config.getMaxConcurrentLoops()
                            + "，请先 /loop stop 停止部分循环");
        }

        String id = String.valueOf(idSequence.incrementAndGet());
        LoopTask task = new LoopTask(id, interval, prompt, client);
        loops.put(id, task);
        lastStartedId.set(id);
        task.start();

        log.info("Loop #{} started: interval={}, prompt='{}'", id, interval, prompt);
        return id;
    }

    /**
     * 停止指定循环
     *
     * @param id 循环 ID
     * @return 是否成功停止（循环不存在时返回 false）
     */
    public boolean stopLoop(String id) {
        LoopTask task = loops.remove(id);
        if (task == null) {
            return false;
        }
        task.stop();
        log.info("Loop #{} stopped after {} iterations", id, task.iterationCount.get());
        return true;
    }

    /**
     * 停止默认循环（最近启动的），供未指定 ID 时使用
     *
     * @return 是否成功停止
     */
    public boolean stopLoop() {
        String id = resolveDefaultLoopId();
        return id != null && stopLoop(id);
    }

    /**
     * 停止所有循环
     *
     * @return 停止的循环数量
     */
    public int stopAll() {
        int count = 0;
        for (String id : new ArrayList<>(loops.keySet())) {
            if (stopLoop(id)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 暂停循环（不取消，但跳过执行）
     *
     * @param id 循环 ID，null 表示默认循环
     * @return 是否成功暂停
     */
    public boolean pauseLoop(String id) {
        LoopTask task = resolveTask(id);
        if (task == null || task.paused.get()) {
            return false;
        }
        task.paused.set(true);
        log.info("Loop #{} paused at iteration {}", task.id, task.iterationCount.get());
        return true;
    }

    /**
     * 恢复循环
     *
     * @param id 循环 ID，null 表示默认循环
     * @return 是否成功恢复
     */
    public boolean resumeLoop(String id) {
        LoopTask task = resolveTask(id);
        if (task == null || !task.paused.get()) {
            return false;
        }
        task.paused.set(false);
        log.info("Loop #{} resumed at iteration {}", task.id, task.iterationCount.get());
        return true;
    }

    /**
     * 获取所有活跃循环的状态（按 ID 排序）
     */
    public List<LoopStatus> listStatus() {
        return loops.values().stream()
                .map(LoopTask::status)
                .sorted(Comparator.comparing(LoopStatus::getLoopId))
                .toList();
    }

    /**
     * 获取指定循环的状态
     *
     * @param id 循环 ID，null 表示默认循环
     * @return LoopStatus，循环不存在时返回 null
     */
    public LoopStatus getStatus(String id) {
        LoopTask task = resolveTask(id);
        return task != null ? task.status() : null;
    }

    /**
     * 是否有循环正在运行
     */
    public boolean isRunning() {
        return !loops.isEmpty();
    }

    /**
     * 指定循环是否已暂停
     *
     * @param id 循环 ID，null 表示默认循环
     */
    public boolean isPaused(String id) {
        LoopTask task = resolveTask(id);
        return task != null && task.paused.get();
    }

    /**
     * Spring 容器关闭时自动清理资源
     */
    @PreDestroy
    public void destroy() {
        log.info("LoopManager shutting down...");
        stopAll();
    }

    // ==================== 内部方法 ====================

    private LoopTask resolveTask(String id) {
        String effectiveId = (id != null) ? id : resolveDefaultLoopId();
        return effectiveId != null ? loops.get(effectiveId) : null;
    }

    private String resolveDefaultLoopId() {
        String id = lastStartedId.get();
        if (id != null && loops.containsKey(id)) {
            return id;
        }
        return loops.keySet().stream().findFirst().orElse(null);
    }

    // ==================== 循环任务 ====================

    /**
     * 单个循环任务：独立调度器 + 独立状态 + 防失控保护
     */
    private class LoopTask {
        private final String id;
        private final String prompt;
        private final Duration interval;
        private final EngineClient client;
        private final Instant startTime = Instant.now();

        private final AtomicBoolean paused = new AtomicBoolean(false);
        private final AtomicInteger iterationCount = new AtomicInteger(0);
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

        private ScheduledExecutorService scheduler;
        private ScheduledFuture<?> future;

        LoopTask(String id, Duration interval, String prompt, EngineClient client) {
            this.id = id;
            this.interval = interval;
            this.prompt = prompt;
            this.client = client;
        }

        void start() {
            ThreadFactory daemonFactory = r -> {
                Thread t = new Thread(r, "loop-scheduler-" + id);
                t.setDaemon(true);
                return t;
            };
            scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory);
            // scheduleWithFixedDelay：单次执行超过间隔时不会堆积补跑
            future = scheduler.scheduleWithFixedDelay(
                    this::executeIteration, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void executeIteration() {
            if (paused.get()) {
                log.debug("Loop #{} is paused, skipping iteration", id);
                return;
            }

            // 防失控：最大迭代次数
            int maxIterations = config.getLoopMaxIterations();
            if (maxIterations > 0 && iterationCount.get() >= maxIterations) {
                log.info("Loop #{} reached max iterations ({}), auto-stopping", id, maxIterations);
                stopFromInside();
                return;
            }

            // 防失控：超时
            int timeoutMinutes = config.getLoopTimeoutMinutes();
            if (timeoutMinutes > 0
                    && Duration.between(startTime, Instant.now()).toMinutes() >= timeoutMinutes) {
                log.info("Loop #{} timed out after {}m, auto-stopping", id, timeoutMinutes);
                stopFromInside();
                return;
            }

            int iteration = iterationCount.incrementAndGet();
            log.info("Loop #{} iteration #{} executing...", id, iteration);

            try {
                client.runCommand(prompt).block();
                consecutiveFailures.set(0);
                log.info("Loop #{} iteration #{} completed", id, iteration);
            } catch (Exception e) {
                int failures = consecutiveFailures.incrementAndGet();
                int maxFailures = config.getLoopMaxConsecutiveFailures();
                log.error("Loop #{} iteration #{} failed ({}/{}): {}",
                        id, iteration, failures, maxFailures, e.getMessage());
                // 防失控：连续失败熔断
                if (maxFailures > 0 && failures >= maxFailures) {
                    log.error("Loop #{} auto-stopped after {} consecutive failures", id, failures);
                    stopFromInside();
                }
            }
        }

        /**
         * 从调度线程内部停止（不能 await 自身线程）
         */
        private void stopFromInside() {
            loops.remove(id);
            if (future != null) {
                future.cancel(false);
            }
            scheduler.shutdown();
        }

        void stop() {
            if (future != null) {
                future.cancel(true);
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.warn("Loop #{} scheduler did not terminate within 5s", id);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        LoopStatus status() {
            return LoopStatus.builder()
                    .loopId(id)
                    .running(true)
                    .paused(paused.get())
                    .iterationCount(iterationCount.get())
                    .consecutiveFailures(consecutiveFailures.get())
                    .prompt(prompt)
                    .interval(interval)
                    .startTime(startTime)
                    .nextExecutionTime(calculateNextExecution())
                    .type(LoopStatus.LoopType.INTERVAL)
                    .build();
        }

        private Instant calculateNextExecution() {
            if (interval == null) {
                return null;
            }
            long elapsed = Instant.now().toEpochMilli() - startTime.toEpochMilli();
            long intervalMs = interval.toMillis();
            long nextMs = ((elapsed / intervalMs) + 1) * intervalMs;
            return startTime.plusMillis(nextMs);
        }
    }
}
