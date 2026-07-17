package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.client.EngineClient;
import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.config.info.LoopEngineeringConfig;
import io.leavesfly.jimi.loop.CommandVerifier;
import io.leavesfly.jimi.loop.GoalVerification;
import io.leavesfly.jimi.loop.GoalVerifier;
import io.leavesfly.jimi.loop.LoopStateManager;
import io.leavesfly.jimi.loop.WorktreeManager;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /goal 命令处理器
 * <p>
 * 设定一个可验证的目标条件，Agent 持续工作直到条件满足。
 * <p>
 * 验证通道（二选一）：
 * - 确定性验证：--verify "cmd"，以命令退出码作为硬证据（推荐，客观不可伪造）
 * - LLM 验证：独立 GoalVerifier 基于状态文件评估（验证者与执行者分离）
 * <p>
 * 启用 worktree 时，Agent 在独立 git worktree 中工作，目标达成并验证通过后
 * 自动提交并合并回目标分支；未达成时保留 worktree 与分支供人工审查。
 * <p>
 * 用法：
 * - /goal [--verify "cmd"] <condition> — 设定目标并开始迭代
 * - /goal continue — 从状态文件恢复上次目标继续迭代（支持跨会话）
 * - /goal stop — 放弃目标
 * - /goal pause — 暂停执行
 * - /goal resume — 恢复执行
 * - /goal status — 查看进度
 */
@Slf4j
@Component
public class GoalCommandHandler implements CommandHandler {

    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final long FAILURE_BACKOFF_BASE_MS = 1000L;
    private static final long FAILURE_BACKOFF_MAX_MS = 30_000L;
    private static final int MAX_FEEDBACK_CHARS = 1500;

    /**
     * 匹配 --verify "cmd" 或 --verify 'cmd'
     */
    private static final Pattern VERIFY_ARG_PATTERN =
            Pattern.compile("--verify\\s+(?:\"([^\"]+)\"|'([^']+)')");

    @Autowired
    private GoalVerifier goalVerifier;

    @Autowired
    private CommandVerifier commandVerifier;

    @Autowired
    private LoopStateManager stateManager;

    @Autowired
    private WorktreeManager worktreeManager;

    @Autowired
    private LoopEngineeringConfig config;

    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicInteger iterationCount = new AtomicInteger(0);
    private volatile Future<?> currentGoalTask;
    private volatile String currentGoal;
    private volatile String currentVerifyCommand;
    private volatile Instant startTime;
    private volatile String stateFile;
    private volatile EngineClient currentEngineClient;
    private volatile Path currentWorkDir;
    private volatile Path currentWorktreeDir;
    private volatile String completionMessage;
    private volatile long baselineTokens;

    public GoalCommandHandler() {
        // 使用 daemon 线程，避免阻止 JVM 退出
        ThreadFactory daemonFactory = r -> {
            Thread t = new Thread(r, "goal-executor");
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(daemonFactory);
    }

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
        return "/goal [--verify \"cmd\"] <condition>  |  /goal continue|stop|pause|resume|status";
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
            case "continue" -> handleContinue(context, out);
            case "help" -> showHelp(out);
            default -> handleStart(context, out);
        }
    }

    // ==================== 子命令处理 ====================

    private void handleStart(CommandContext context, OutputFormatter out) {
        GoalArgs goalArgs = parseGoalArgs(context);
        String goalCondition = goalArgs.condition();
        if (goalCondition.isBlank()) {
            out.printError("目标条件不能为空");
            out.printInfo("用法: /goal [--verify \"cmd\"] <condition>");
            return;
        }
        startGoal(context, out, goalCondition, goalArgs.verifyCommand(), 0, true);
    }

    /**
     * 从状态文件恢复上次目标继续迭代（支持跨会话续跑）
     */
    private void handleContinue(CommandContext context, OutputFormatter out) {
        Path workDir = context.getEngineClient().getWorkDir();
        String sf = config.getDefaultStateFile();

        String goal = stateManager.parseGoalCondition(workDir, sf);
        if (goal == null || goal.isBlank()) {
            out.printError("未找到可续跑的目标（状态文件不存在或无目标记录）: " + sf);
            return;
        }
        String verifyCmd = stateManager.parseVerifyCommand(workDir, sf);
        int doneIterations = stateManager.parseIterationCount(workDir, sf);

        out.printInfo("从状态文件恢复目标（已完成 " + doneIterations + " 步）: " + goal);
        startGoal(context, out, goal, verifyCmd, doneIterations, false);
    }

    /**
     * 启动 goal 循环的统一入口
     *
     * @param resumeIterations 已完成的迭代数（续跑时 > 0）
     * @param initState        是否重新初始化状态文件（续跑时为 false）
     */
    private void startGoal(CommandContext context, OutputFormatter out, String goalCondition,
                           String verifyCommand, int resumeIterations, boolean initState) {
        if (!config.isEnabled()) {
            out.printError("Loop Engineering 功能已禁用（可在配置 loop_engineering.enabled 中开启）");
            return;
        }
        if (running.get()) {
            out.printError("已有目标正在执行中。使用 /goal stop 终止当前目标。");
            return;
        }

        EngineClient client = context.getEngineClient();
        Path workDir = client.getWorkDir();

        this.currentGoal = goalCondition;
        this.currentVerifyCommand = verifyCommand;
        this.stateFile = config.getDefaultStateFile();
        this.startTime = Instant.now();
        this.iterationCount.set(resumeIterations);
        this.running.set(true);
        this.paused.set(false);
        this.completionMessage = null;
        // 缓存 EngineClient 和 workDir，避免将整个 CommandContext 传入后台线程
        this.currentEngineClient = client;
        this.currentWorkDir = workDir;
        // 记录 token 基线，goal 预算按本次任务独立计量
        this.baselineTokens = safeTokenCount(client);

        // Worktree 隔离：创建独立工作区，验证与执行都在 worktree 中进行
        Path executionDir = workDir;
        Path worktreeDir = null;
        String targetBranch = null;
        if (config.isWorktreeEnabled() && worktreeManager.isGitRepo(workDir)) {
            try {
                targetBranch = worktreeManager.getCurrentBranch(workDir);
                worktreeDir = worktreeManager.createWorktree(
                        "goal-" + UUID.randomUUID().toString().substring(0, 8), workDir);
                executionDir = worktreeDir;
            } catch (Exception e) {
                out.printWarning("Worktree 创建失败，回退到主工作目录执行: " + e.getMessage());
                worktreeDir = null;
                executionDir = workDir;
            }
        }
        this.currentWorktreeDir = worktreeDir;

        // 初始化状态文件（续跑时保留已有状态）
        if (initState && config.isStateAutoUpdate()) {
            stateManager.initializeState(workDir, stateFile, goalCondition, verifyCommand);
        }

        out.println();
        out.printSuccess(resumeIterations > 0 ? "Goal 已恢复，继续自动迭代" : "Goal 已设定，开始自动迭代");
        out.printInfo("  目标: " + goalCondition);
        out.printInfo("  验证方式: " + (verifyCommand != null
                ? "命令验证（退出码）: " + verifyCommand
                : "LLM 验证（基于状态文件）"));
        if (worktreeDir != null) {
            out.printInfo("  隔离工作区: " + worktreeDir);
        }
        out.printInfo("  最大步数: " + config.getGoalMaxIterations()
                + (resumeIterations > 0 ? "（已完成 " + resumeIterations + " 步）" : ""));
        out.printInfo("  超时: " + config.getGoalTimeoutMinutes() + " 分钟");
        out.printInfo("  使用 /goal status 查看进度, /goal stop 终止");
        out.println();

        // 在后台线程启动 goal loop（仅传递必要参数，不传递 context）
        final Path execDir = executionDir;
        final Path wtDir = worktreeDir;
        final String branch = targetBranch;
        currentGoalTask = executor.submit(() ->
                runGoalLoop(client, workDir, execDir, wtDir, branch, goalCondition, verifyCommand));
    }

    private void handleStop(OutputFormatter out) {
        if (!running.get()) {
            out.printWarning("当前没有运行中的目标");
            return;
        }

        running.set(false);
        if (currentGoalTask != null) {
            currentGoalTask.cancel(true);
            currentGoalTask = null;
        }

        out.println();
        out.printSuccess("Goal 已终止");
        out.printInfo("  共执行: " + iterationCount.get() + " 次迭代");
        if (currentWorktreeDir != null) {
            out.printInfo("  隔离工作区保留在: " + currentWorktreeDir + "（变更保留在临时分支，可人工审查合并）");
        }
        out.printInfo("  可使用 /goal continue 从状态文件恢复继续迭代");
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
            if (completionMessage != null) {
                out.printInfo("  上次结果: " + completionMessage);
            }
        } else {
            out.printSuccess("Goal 执行状态");
            out.printInfo("  目标: " + currentGoal);
            out.printInfo("  验证方式: " + (currentVerifyCommand != null
                    ? "命令验证: " + currentVerifyCommand
                    : "LLM 验证"));
            out.printInfo("  状态: " + (paused.get() ? "已暂停" : "运行中"));
            out.printInfo("  已执行: " + iterationCount.get() + " / " + config.getGoalMaxIterations() + " 步");
            if (currentWorktreeDir != null) {
                out.printInfo("  隔离工作区: " + currentWorktreeDir);
            }
            if (currentEngineClient != null) {
                long consumed = Math.max(0, safeTokenCount(currentEngineClient) - baselineTokens);
                out.printInfo("  本次 Token 消耗: " + consumed + " / " + config.getGoalMaxTokens());
            }
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
        out.println("  /goal <condition>              设定目标条件并开始迭代（LLM 验证）");
        out.println("  /goal --verify \"cmd\" <condition> 设定目标，以命令退出码判定达成（推荐）");
        out.println("  /goal continue                 从状态文件恢复上次目标继续迭代");
        out.println("  /goal stop                     放弃当前目标");
        out.println("  /goal pause                    暂停执行");
        out.println("  /goal resume                   恢复执行");
        out.println("  /goal status                   查看进度");
        out.println("  /goal help                     显示帮助");
        out.println();
        out.println("  示例:");
        out.println("    /goal --verify \"mvn -q test\" 所有测试通过");
        out.println("    /goal --verify \"mvn compile -q\" 编译成功且无 ERROR");
        out.println("    /goal 测试覆盖率达到 80%");
        out.println();
        out.println("  说明:");
        out.println("    --verify 命令的退出码是目标达成的硬证据，避免 Agent 自述误判");
        out.println("    启用 worktree 时，Agent 在隔离工作区执行，达成后自动合并回当前分支");
        out.println();
    }

    // ==================== Goal Loop 核心逻辑 ====================

    /**
     * Goal Loop 主循环（在后台线程运行）
     */
    private void runGoalLoop(EngineClient engineClient, Path workDir, Path executionDir,
                             Path worktreeDir, String targetBranch,
                             String goalCondition, String verifyCommand) {
        int maxIterations = config.getGoalMaxIterations();
        int verifyInterval = config.getGoalVerifyInterval();
        Duration timeout = Duration.ofMinutes(config.getGoalTimeoutMinutes());
        long maxTokens = config.getGoalMaxTokens();
        boolean satisfied = false;
        String lastVerifyReason = null;

        log.info("Goal loop started: condition='{}', verifyCmd='{}', worktree={}, maxIter={}, timeout={}m, maxTokens={}",
                goalCondition, verifyCommand, worktreeDir, maxIterations, timeout.toMinutes(), maxTokens);

        int consecutiveFailures = 0;
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

                // 检查 token 预算（按本次 goal 独立计量：当前值 - 启动基线）
                if (maxTokens > 0) {
                    long consumed = safeTokenCount(engineClient) - baselineTokens;
                    if (consumed >= maxTokens) {
                        log.info("Goal loop reached token budget: {} >= {}", consumed, maxTokens);
                        break;
                    }
                }

                // 检查最大迭代次数（自增前判断，避免计数越界）
                if (iterationCount.get() >= maxIterations) {
                    log.info("Goal loop reached max iterations: {}", maxIterations);
                    break;
                }
                int currentIteration = iterationCount.incrementAndGet();

                log.info("Goal iteration #{} starting...", currentIteration);

                // 1. 执行者工作一步
                String workerPrompt = buildWorkerPrompt(
                        goalCondition, currentIteration, worktreeDir, workDir, verifyCommand, lastVerifyReason);
                try {
                    engineClient.runCommand(workerPrompt).block();
                    consecutiveFailures = 0;
                } catch (Exception e) {
                    consecutiveFailures++;
                    log.error("Goal iteration #{} execution failed ({}/{}): {}",
                            currentIteration, consecutiveFailures, MAX_CONSECUTIVE_FAILURES, e.getMessage());
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        log.error("Goal loop aborted after {} consecutive failures", consecutiveFailures);
                        break;
                    }
                    // 指数退避，避免快速失败时忙等空烧资源
                    long backoffMs = Math.min(
                            FAILURE_BACKOFF_BASE_MS * (1L << (consecutiveFailures - 1)),
                            FAILURE_BACKOFF_MAX_MS);
                    Thread.sleep(backoffMs);
                    continue;
                }

                // 2. 每 N 步验证一次
                if (currentIteration % verifyInterval == 0) {
                    GoalVerification verification = doVerify(
                            verifyCommand, executionDir, workDir, goalCondition);

                    if (verification != null && verification.isSatisfied()) {
                        satisfied = true;
                        log.info("Goal satisfied at iteration #{}: {}", currentIteration, verification.getReason());
                        if (config.isStateAutoUpdate()) {
                            stateManager.appendTask(workDir, stateFile, "目标已达成: " + verification.getReason(), true);
                            stateManager.updateStats(workDir, stateFile, currentIteration);
                        }
                        break;
                    }

                    lastVerifyReason = verification != null ? verification.getReason() : null;
                    log.debug("Goal not yet satisfied: {}", lastVerifyReason);
                }

                // 更新状态
                if (config.isStateAutoUpdate()) {
                    stateManager.updateStats(workDir, stateFile, currentIteration);
                    stateManager.updateTokenStats(workDir, stateFile,
                            Math.max(0, safeTokenCount(engineClient) - baselineTokens));
                }
            }
        } catch (InterruptedException e) {
            log.info("Goal loop interrupted");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Goal loop unexpected error: {}", e.getMessage(), e);
        } finally {
            running.set(false);
            completionMessage = finalizeWorktree(worktreeDir, workDir, targetBranch, satisfied);
            if (completionMessage != null) {
                log.info("Goal finalization: {}", completionMessage);
            }
            log.info("Goal loop ended after {} iterations (satisfied={})", iterationCount.get(), satisfied);
        }
    }

    /**
     * 执行一次目标验证
     * 优先使用确定性命令验证（退出码硬证据），否则回退到 LLM 验证
     */
    private GoalVerification doVerify(String verifyCommand, Path executionDir,
                                      Path workDir, String goalCondition) {
        if (verifyCommand != null && !verifyCommand.isBlank()) {
            return commandVerifier.verify(verifyCommand, executionDir);
        }
        String currentState = stateManager.readState(workDir, stateFile);
        return goalVerifier.verify(goalCondition, currentState).block();
    }

    /**
     * Goal 结束后处理 worktree：提交变更，按配置合并/保留
     *
     * @return 完成信息（无 worktree 时返回 null）
     */
    private String finalizeWorktree(Path worktreeDir, Path workDir, String targetBranch, boolean satisfied) {
        if (worktreeDir == null) {
            return satisfied ? "目标已达成" : null;
        }

        String branch = worktreeManager.getBranchName(worktreeDir);
        String branchInfo = branch != null ? branch : "(未知分支)";

        // 无论达成与否，先提交 worktree 中的变更，避免未提交修改丢失
        worktreeManager.commitAll(worktreeDir, "jimi: goal iteration changes");

        if (!satisfied) {
            return String.format("Goal 未达成，变更保留在 worktree 分支 %s（%s），可人工审查后合并",
                    branchInfo, worktreeDir);
        }
        if (!config.isWorktreeAutoMerge()) {
            return String.format("目标已达成，变更保留在 worktree 分支 %s（%s），请审查后合并",
                    branchInfo, worktreeDir);
        }
        if (targetBranch == null) {
            return String.format("目标已达成，但无法确定目标分支，变更保留在分支 %s", branchInfo);
        }

        WorktreeManager.MergeResult merge = worktreeManager.mergeWorktree(worktreeDir, workDir, targetBranch);
        if (!merge.isSuccess()) {
            return String.format("目标已达成，但合并到 %s 失败（%s），变更保留在分支 %s（%s）",
                    targetBranch, merge.getMessage(), branchInfo, worktreeDir);
        }

        if (config.isWorktreeAutoCleanup()) {
            worktreeManager.cleanupWorktree(worktreeDir, workDir);
            return String.format("目标已达成，变更已合并到 %s，worktree 已清理", targetBranch);
        }
        return String.format("目标已达成，变更已合并到 %s，worktree 保留在 %s", targetBranch, worktreeDir);
    }

    /**
     * 停止当前 goal 循环（供外部调用，如 ShellUI 关闭时）
     */
    public void stopGoal() {
        if (!running.get()) {
            return;
        }
        running.set(false);
        if (currentGoalTask != null) {
            currentGoalTask.cancel(true);
            currentGoalTask = null;
        }
        log.info("Goal stopped externally after {} iterations", iterationCount.get());
    }

    /**
     * 检查是否有 goal 正在运行
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Spring 容器关闭时自动清理资源
     */
    @PreDestroy
    public void destroy() {
        log.info("GoalCommandHandler shutting down...");
        stopGoal();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Goal executor did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 构建工作者 prompt
     */
    private String buildWorkerPrompt(String goalCondition, int iteration, Path worktreeDir,
                                     Path workDir, String verifyCommand, String lastVerifyReason) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("""
                你正在执行一个目标驱动的任务。这是第 %d 步迭代。
                
                目标条件: %s
                """, iteration, goalCondition));

        if (worktreeDir != null) {
            sb.append(String.format("""
                    
                    【工作区隔离】所有代码修改必须在隔离工作区中进行：
                    - 工作区路径: %s
                    - 读写文件使用该工作区下的绝对路径；执行 bash 命令时先 cd %s
                    - 不要直接修改主仓库目录 %s 下的文件
                    """, worktreeDir.toAbsolutePath(), worktreeDir.toAbsolutePath(), workDir.toAbsolutePath()));
        }

        if (verifyCommand != null) {
            sb.append(String.format("""
                    
                    【判定方式】目标是否达成由以下验证命令的退出码判定（0 = 达成）：
                    验证命令: %s
                    请确保你的修改能让该命令成功执行，可以主动运行它确认。
                    """, verifyCommand));
        }

        if (lastVerifyReason != null && !lastVerifyReason.isBlank()) {
            sb.append(String.format("""
                    
                    【上次验证未通过】原因/输出：
                    %s
                    请针对上述问题修复。
                    """, truncate(lastVerifyReason)));
        }

        sb.append(String.format("""
                
                请向目标方向推进一步。执行具体的操作（如修改代码、运行测试等），而不仅仅是描述要做什么。
                
                重要：完成本步后，请将「本步做了什么」以及「可验证的当前结果」追加写入状态文件 %s。
                追加时保持文件现有章节结构（## 已完成 / ## 进行中 / ## 待处理 / ## 统计）和统计行格式不变，
                只在对应章节下追加 "- [ ] #id: 内容" 格式的行，如实、完整地记录关键结果，不要遗漏。
                """, stateFile));

        return sb.toString();
    }

    // ==================== 工具方法 ====================

    /**
     * 从原始输入解析目标条件与 --verify 验证命令
     * 原始输入保留引号，args 按空白切分无法正确还原带空格的命令
     */
    private GoalArgs parseGoalArgs(CommandContext context) {
        String source = context.getRawInput();
        if (source == null || source.isBlank()) {
            source = "/" + context.getCommandName() + " " + context.getArgsAsString();
        }

        String verifyCommand = null;
        Matcher matcher = VERIFY_ARG_PATTERN.matcher(source);
        if (matcher.find()) {
            verifyCommand = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            source = source.substring(0, matcher.start()) + source.substring(matcher.end());
        }

        // 去掉首个 token（命令名，如 /goal）
        String condition = source.replaceFirst("^\\s*\\S+", "").trim();
        return new GoalArgs(condition, verifyCommand);
    }

    private long safeTokenCount(EngineClient client) {
        try {
            return client.getTokenCount();
        } catch (Exception e) {
            return 0;
        }
    }

    private String truncate(String text) {
        if (text == null || text.length() <= MAX_FEEDBACK_CHARS) {
            return text;
        }
        return text.substring(0, MAX_FEEDBACK_CHARS) + "\n...(截断)";
    }

    /**
     * 解析后的 goal 参数
     */
    private record GoalArgs(String condition, String verifyCommand) {
    }
}
