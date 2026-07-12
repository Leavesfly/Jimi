package io.leavesfly.jimi.core.loop;

import io.leavesfly.jimi.client.EngineClient;
import io.leavesfly.jimi.config.info.LoopEngineeringConfig;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 循环调度核心管理器
 * <p>
 * 管理 /loop 命令的生命周期：启动、暂停、恢复、停止。
 * 使用 ScheduledExecutorService 按指定间隔重复提交 prompt 到引擎。
 */
@Slf4j
@Service
public class LoopManager {

    @Autowired
    private LoopEngineeringConfig config;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> currentLoop;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicInteger iterationCount = new AtomicInteger(0);

    private String currentPrompt;
    private Duration currentInterval;
    private Instant startTime;
    private EngineClient currentClient;

    /**
     * 启动定时循环
     *
     * @param interval 循环间隔
     * @param prompt   每次循环执行的 prompt
     * @param client   EngineClient 实例
     * @throws IllegalStateException 如果已有循环在运行
     */
    public synchronized void startLoop(Duration interval, String prompt, EngineClient client) {
        if (running.get()) {
            throw new IllegalStateException("已有循环正在运行，请先 /loop stop");
        }

        this.currentPrompt = prompt;
        this.currentInterval = interval;
        this.currentClient = client;
        this.startTime = Instant.now();
        this.iterationCount.set(0);
        this.paused.set(false);
        this.running.set(true);

        // 创建调度器（使用 daemon 线程，避免阻止 JVM 退出）
        ThreadFactory daemonFactory = r -> {
            Thread t = new Thread(r, "loop-scheduler");
            t.setDaemon(true);
            return t;
        };
        scheduler = Executors.newScheduledThreadPool(config.getScheduleThreadPoolSize(), daemonFactory);
        currentLoop = scheduler.scheduleAtFixedRate(
                this::executeIteration,
                0,
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );

        log.info("Loop started: interval={}, prompt='{}'", interval, prompt);
    }

    /**
     * 停止当前循环
     */
    public synchronized void stopLoop() {
        if (!running.get()) {
            return;
        }

        running.set(false);
        paused.set(false);

        if (currentLoop != null) {
            currentLoop.cancel(true); // true: 中断正在执行的任务
            currentLoop = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow(); // 强制关闭，不等待任务完成
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Loop scheduler did not terminate within 5s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }

        log.info("Loop stopped after {} iterations", iterationCount.get());
    }

    /**
     * Spring 容器关闭时自动清理资源
     */
    @PreDestroy
    public void destroy() {
        log.info("LoopManager shutting down...");
        stopLoop();
    }

    /**
     * 暂停循环（不取消，但跳过执行）
     */
    public void pauseLoop() {
        if (running.get() && !paused.get()) {
            paused.set(true);
            log.info("Loop paused at iteration {}", iterationCount.get());
        }
    }

    /**
     * 恢复循环
     */
    public void resumeLoop() {
        if (running.get() && paused.get()) {
            paused.set(false);
            log.info("Loop resumed at iteration {}", iterationCount.get());
        }
    }

    /**
     * 获取当前循环状态
     *
     * @return LoopStatus 对象
     */
    public LoopStatus getStatus() {
        return LoopStatus.builder()
                .running(running.get())
                .paused(paused.get())
                .iterationCount(iterationCount.get())
                .prompt(currentPrompt)
                .interval(currentInterval)
                .startTime(startTime)
                .nextExecutionTime(calculateNextExecution())
                .type(LoopStatus.LoopType.INTERVAL)
                .build();
    }

    /**
     * 检查是否有循环正在运行
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 检查是否已暂停
     */
    public boolean isPaused() {
        return paused.get();
    }

    // ==================== 内部方法 ====================

    /**
     * 执行一次迭代
     */
    private void executeIteration() {
        if (paused.get()) {
            log.debug("Loop is paused, skipping iteration");
            return;
        }

        int iteration = iterationCount.incrementAndGet();
        log.info("Loop iteration #{} executing...", iteration);

        try {
            currentClient.runCommand(currentPrompt).block();
            log.info("Loop iteration #{} completed", iteration);
        } catch (Exception e) {
            log.error("Loop iteration #{} failed: {}", iteration, e.getMessage());
            // 不停止循环，继续下一次
        }
    }

    /**
     * 计算下次执行时间
     */
    private Instant calculateNextExecution() {
        if (!running.get() || currentInterval == null || startTime == null) {
            return null;
        }
        long elapsed = Instant.now().toEpochMilli() - startTime.toEpochMilli();
        long intervalMs = currentInterval.toMillis();
        long nextMs = ((elapsed / intervalMs) + 1) * intervalMs;
        return startTime.plusMillis(nextMs);
    }
}
