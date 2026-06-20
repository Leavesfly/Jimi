package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.config.info.LoopEngineeringConfig;
import io.leavesfly.jimi.core.loop.GoalVerification;
import io.leavesfly.jimi.core.loop.GoalVerifier;
import io.leavesfly.jimi.core.loop.LoopStateManager;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * /goal 命令处理器
 * <p>
 * 设定一个可验证的目标条件，Agent 持续工作直到条件满足。
 * 核心设计：验证者和执行者使用不同的判断，避免自己评判自己。
 * <p>
 * 用法：
 * - /goal <condition> — 设定目标并开始迭代
 * - /goal stop — 放弃目标
 * - /goal pause — 暂停执行
 * - /goal resume — 恢复执行
 * - /goal status — 查看进度
 */
@Slf4j
@Component
public class GoalCommandHandler implements CommandHandler {

    @Autowired
    private GoalVerifier goalVerifier;

    @Autowired
    private LoopStateManager stateManager;

    @Autowired
    private LoopEngineeringConfig config;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicInteger iterationCount = new AtomicInteger(0);
    private volatile Future<?> currentGoalTask;
    private volatile String currentGoal;
    private volatile Instant startTime;
    private volatile String stateFile;

    @Override
    public String getName() {
        return "goal";
    }

    @Override
    public String getDescription() {
        return "设定目标条件，Agent 自动迭代直到满足";
    }

    @Override
    public String getUsage() {
        return "/goal <condition>  |  /goal stop|pause|resume|status";
    }

    @Override
    public String getCategory() {
        return "automation";
    }

    @Override
    public List<String> getAliases() {
        return List.of();
    }

    @Override
    public void execute(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();

        if (context.getArgCount() == 0) {
            showHelp(out);
            return;
        }

        String subCommand = context.getArg(0).toLowerCase();

        switch (subCommand) {
            case "stop" -> handleStop(out);
            case "pause" -> handlePause(out);
            case "resume" -> handleResume(out);
            case "status" -> handleStatus(out);
            case "help" -> showHelp(out);
            default -> handleStart(context, out);
        }
    }

    // ==================== 子命令处理 ====================

    private void handleStart(CommandContext context, OutputFormatter out) {
        if (running.get()) {
            out.printError("已有目标正在执行中。使用 /goal stop 终止当前目标。");
            return;
        }

        // 拼接完整的目标条件
        String goalCondition = context.getArgsAsString();
        if (goalCondition.isBlank()) {
            out.printError("目标条件不能为空");
            return;
        }

        Path workDir = context.getEngineClient().getWorkDir();
        this.currentGoal = goalCondition;
        this.stateFile = config.getDefaultStateFile();
        this.startTime = Instant.now();
        this.iterationCount.set(0);
        this.running.set(true);
        this.paused.set(false);

        // 初始化状态文件
        if (config.isStateAutoUpdate()) {
            stateManager.initializeState(workDir, stateFile, goalCondition);
        }

        out.println();
        out.printSuccess("Goal 已设定，开始自动迭代");
        out.printInfo("  目标: " + goalCondition);
        out.printInfo("  最大步数: " + config.getGoalMaxIterations());
        out.printInfo("  超时: " + config.getGoalTimeoutMinutes() + " 分钟");
        out.printInfo("  使用 /goal status 查看进度, /goal stop 终止");
        out.println();

        // 在后台线程启动 goal loop
        currentGoalTask = executor.submit(() -> runGoalLoop(context, goalCondition));
    }

    private void handleStop(OutputFormatter out) {
        if (!running.get()) {
            out.printWarning("当前没有运行中的目标");
            return;
        }

        running.set(false);
        if (currentGoalTask != null) {
            currentGoalTask.cancel(true);
        }

        out.println();
        out.printSuccess("Goal 已终止");
        out.printInfo("  共执行: " + iterationCount.get() + " 次迭代");
        out.println();
    }

    private void handlePause(OutputFormatter out) {
        if (!running.get()) {
            out.printWarning("当前没有运行中的目标");
            return;
        }
        paused.set(true);
        out.printSuccess("Goal 已暂停，使用 /goal resume 恢复");
    }

    private void handleResume(OutputFormatter out) {
        if (!running.get()) {
            out.printWarning("当前没有运行中的目标");
            return;
        }
        if (!paused.get()) {
            out.printWarning("目标未暂停");
            return;
        }
        paused.set(false);
        out.printSuccess("Goal 已恢复执行");
    }

    private void handleStatus(OutputFormatter out) {
        out.println();
        if (!running.get()) {
            out.printInfo("当前没有运行中的目标");
        } else {
            out.printSuccess("Goal 执行状态");
            out.printInfo("  目标: " + currentGoal);
            out.printInfo("  状态: " + (paused.get() ? "已暂停" : "运行中"));
            out.printInfo("  已执行: " + iterationCount.get() + " / " + config.getGoalMaxIterations() + " 步");
            if (startTime != null) {
                Duration elapsed = Duration.between(startTime, Instant.now());
                out.printInfo("  已用时: " + elapsed.toMinutes() + " 分钟");
            }
        }
        out.println();
    }

    private void showHelp(OutputFormatter out) {
        out.println();
        out.printSuccess("/goal 命令 — Loop Engineering 目标驱动迭代");
        out.println();
        out.println("  /goal <condition>    设定目标条件并开始迭代");
        out.println("  /goal stop           放弃当前目标");
        out.println("  /goal pause          暂停执行");
        out.println("  /goal resume         恢复执行");
        out.println("  /goal status         查看进度");
        out.println("  /goal help           显示帮助");
        out.println();
        out.println("  示例:");
        out.println("    /goal 所有测试通过且 lint 无警告");
        out.println("    /goal mvn compile 成功且无 ERROR");
        out.println("    /goal 测试覆盖率达到 80%");
        out.println();
    }

    // ==================== Goal Loop 核心逻辑 ====================

    /**
     * Goal Loop 主循环（在后台线程运行）
     */
    private void runGoalLoop(CommandContext context, String goalCondition) {
        Path workDir = context.getEngineClient().getWorkDir();
        int maxIterations = config.getGoalMaxIterations();
        int verifyInterval = config.getGoalVerifyInterval();
        Duration timeout = Duration.ofMinutes(config.getGoalTimeoutMinutes());

        log.info("Goal loop started: condition='{}', maxIter={}, timeout={}m",
                goalCondition, maxIterations, timeout.toMinutes());

        try {
            while (running.get()) {
                // 检查暂停
                while (paused.get() && running.get()) {
                    Thread.sleep(500);
                }
                if (!running.get()) break;

                // 检查超时
                if (Duration.between(startTime, Instant.now()).compareTo(timeout) > 0) {
                    log.info("Goal loop timed out after {} minutes", timeout.toMinutes());
                    break;
                }

                // 检查最大迭代次数
                int currentIteration = iterationCount.incrementAndGet();
                if (currentIteration > maxIterations) {
                    log.info("Goal loop reached max iterations: {}", maxIterations);
                    break;
                }

                log.info("Goal iteration #{} starting...", currentIteration);

                // 1. 执行者工作一步
                String workerPrompt = buildWorkerPrompt(goalCondition, currentIteration);
                try {
                    context.getEngineClient().runCommand(workerPrompt).block();
                } catch (Exception e) {
                    log.error("Goal iteration #{} execution failed: {}", currentIteration, e.getMessage());
                    continue;
                }

                // 2. 每 N 步验证一次
                if (currentIteration % verifyInterval == 0) {
                    String currentState = stateManager.readState(workDir, stateFile);
                    GoalVerification verification = goalVerifier.verify(goalCondition, currentState).block();

                    if (verification != null && verification.isSatisfied()) {
                        log.info("Goal satisfied at iteration #{}: {}", currentIteration, verification.getReason());
                        if (config.isStateAutoUpdate()) {
                            stateManager.appendTask(workDir, stateFile, "目标已达成: " + verification.getReason(), true);
                            stateManager.updateStats(workDir, stateFile, currentIteration);
                        }
                        break;
                    }

                    log.debug("Goal not yet satisfied: {}", verification != null ? verification.getReason() : "unknown");
                }

                // 更新状态
                if (config.isStateAutoUpdate()) {
                    stateManager.updateStats(workDir, stateFile, currentIteration);
                }
            }
        } catch (InterruptedException e) {
            log.info("Goal loop interrupted");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Goal loop unexpected error: {}", e.getMessage(), e);
        } finally {
            running.set(false);
            log.info("Goal loop ended after {} iterations", iterationCount.get());
        }
    }

    /**
     * 构建工作者 prompt
     */
    private String buildWorkerPrompt(String goalCondition, int iteration) {
        return String.format("""
                你正在执行一个目标驱动的任务。这是第 %d 步迭代。
                
                目标条件: %s
                
                请向目标方向推进一步。执行具体的操作（如修改代码、运行测试等），而不仅仅是描述要做什么。
                完成后简要说明你做了什么以及当前状态。
                """, iteration, goalCondition);
    }
}
