package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.config.info.LoopEngineeringConfig;
import io.leavesfly.jimi.loop.LoopManager;
import io.leavesfly.jimi.loop.LoopStatus;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /loop 命令处理器
 * <p>
 * 按指定时间间隔重复执行一个 prompt，支持多循环并发（受
 * loop_engineering.max_concurrent_loops 限制），并带有防失控保护：
 * 最大迭代次数、超时、连续失败熔断（达到后自动停止）。
 * <p>
 * 用法：
 * - /loop <interval> <prompt> — 启动循环，返回循环 ID
 * - /loop stop [id|all] — 停止指定/全部循环（默认最近启动的）
 * - /loop pause [id] — 暂停循环
 * - /loop resume [id] — 恢复循环
 * - /loop status — 查看所有循环状态
 */
@Slf4j
@Component
public class LoopCommandHandler implements CommandHandler {

    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?");

    @Autowired
    private LoopManager loopManager;

    @Autowired
    private LoopEngineeringConfig config;

    @Override
    public String getName() {
        return "loop";
    }

    @Override
    public String getDescription() {
        return "按时间间隔重复执行 prompt";
    }

    @Override
    public String getUsage() {
        return "/loop <interval> <prompt>  |  /loop stop [id|all]  |  /loop pause|resume [id]  |  /loop status";
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
            case "stop" -> handleStop(context, out);
            case "pause" -> handlePause(context, out);
            case "resume" -> handleResume(context, out);
            case "status" -> handleStatus(out);
            case "help" -> showHelp(out);
            default -> handleStart(context, out);
        }
    }

    // ==================== 子命令处理 ====================

    private void handleStart(CommandContext context, OutputFormatter out) {
        if (!config.isEnabled()) {
            out.printError("Loop Engineering 功能已禁用（可在配置 loop_engineering.enabled 中开启）");
            return;
        }
        if (context.getArgCount() < 2) {
            out.printError("用法: /loop <interval> <prompt>");
            out.printInfo("  interval 格式: 30s, 5m, 1h, 2h30m");
            return;
        }

        // 解析 interval
        String intervalStr = context.getArg(0);
        Duration interval = parseDuration(intervalStr);
        if (interval == null || interval.isZero()) {
            out.printError("无效的时间间隔: " + intervalStr);
            out.printInfo("  支持格式: 30s, 5m, 1h, 2h30m");
            return;
        }

        // 拼接 prompt（第二个参数起的所有内容）
        StringBuilder promptBuilder = new StringBuilder();
        for (int i = 1; i < context.getArgCount(); i++) {
            if (!promptBuilder.isEmpty()) {
                promptBuilder.append(" ");
            }
            promptBuilder.append(context.getArg(i));
        }
        String prompt = promptBuilder.toString();

        if (prompt.isBlank()) {
            out.printError("prompt 不能为空");
            return;
        }

        // 启动循环
        try {
            String loopId = loopManager.startLoop(interval, prompt, context.getEngineClient());
            out.println();
            out.printSuccess("Loop #" + loopId + " 已启动");
            out.printInfo("  间隔: " + formatDuration(interval));
            out.printInfo("  Prompt: " + prompt);
            out.printInfo("  防护: 最大 " + limitText(config.getLoopMaxIterations(), "步")
                    + ", " + limitText(config.getLoopTimeoutMinutes(), "分钟")
                    + ", 连续失败 " + config.getLoopMaxConsecutiveFailures() + " 次熔断");
            out.printInfo("  使用 /loop stop " + loopId + " 停止, /loop status 查看状态");
            out.println();
        } catch (IllegalStateException e) {
            out.printError(e.getMessage());
        }
    }

    private void handleStop(CommandContext context, OutputFormatter out) {
        if (!loopManager.isRunning()) {
            out.printWarning("当前没有运行中的循环");
            return;
        }

        String target = context.getArgCount() > 1 ? context.getArg(1) : null;

        // /loop stop all — 停止全部
        if ("all".equalsIgnoreCase(target)) {
            int count = loopManager.stopAll();
            out.println();
            out.printSuccess("已停止全部 " + count + " 个循环");
            out.println();
            return;
        }

        // /loop stop [id] — 停止指定或默认循环
        LoopStatus status = loopManager.getStatus(target);
        if (status == null) {
            out.printError(target != null ? "循环 #" + target + " 不存在" : "当前没有运行中的循环");
            return;
        }
        loopManager.stopLoop(status.getLoopId());
        out.println();
        out.printSuccess("Loop #" + status.getLoopId() + " 已停止");
        out.printInfo("  共执行: " + status.getIterationCount() + " 次迭代");
        out.println();
    }

    private void handlePause(CommandContext context, OutputFormatter out) {
        if (!loopManager.isRunning()) {
            out.printWarning("当前没有运行中的循环");
            return;
        }
        String target = context.getArgCount() > 1 ? context.getArg(1) : null;
        if (loopManager.isPaused(target)) {
            out.printWarning("循环已处于暂停状态");
            return;
        }
        if (loopManager.pauseLoop(target)) {
            out.printSuccess("Loop 已暂停，使用 /loop resume 恢复");
        } else {
            out.printError(target != null ? "循环 #" + target + " 不存在" : "暂停失败");
        }
    }

    private void handleResume(CommandContext context, OutputFormatter out) {
        if (!loopManager.isRunning()) {
            out.printWarning("当前没有运行中的循环");
            return;
        }
        String target = context.getArgCount() > 1 ? context.getArg(1) : null;
        if (loopManager.resumeLoop(target)) {
            out.printSuccess("Loop 已恢复运行");
        } else {
            out.printWarning(target != null ? "循环 #" + target + " 不存在或未暂停" : "循环未暂停");
        }
    }

    private void handleStatus(OutputFormatter out) {
        List<LoopStatus> statuses = loopManager.listStatus();
        out.println();
        if (statuses.isEmpty()) {
            out.printInfo("当前没有运行中的循环");
        } else {
            out.printSuccess("Loop 运行状态（共 " + statuses.size() + " 个，上限 "
                    + config.getMaxConcurrentLoops() + "）");
            for (LoopStatus status : statuses) {
                out.println();
                out.printInfo("  [#" + status.getLoopId() + "] "
                        + (status.isPaused() ? "已暂停" : "运行中")
                        + " | 已执行 " + status.getIterationCount() + " 次"
                        + (status.getConsecutiveFailures() > 0
                                ? " | 连续失败 " + status.getConsecutiveFailures() + " 次" : ""));
                out.printInfo("      间隔: " + formatDuration(status.getInterval())
                        + " | Prompt: " + status.getPrompt());
                if (status.getStartTime() != null) {
                    out.printInfo("      启动时间: " + formatInstant(status.getStartTime())
                            + (status.getNextExecutionTime() != null && !status.isPaused()
                                    ? " | 下次执行: " + formatInstant(status.getNextExecutionTime())
                                    : ""));
                }
            }
        }
        out.println();
    }

    private void showHelp(OutputFormatter out) {
        out.println();
        out.printSuccess("/loop 命令 — Loop Engineering 循环调度");
        out.println();
        out.println("  /loop <interval> <prompt>   启动循环（返回循环 ID）");
        out.println("  /loop stop [id|all]         停止指定/全部循环（默认最近启动的）");
        out.println("  /loop pause [id]            暂停循环");
        out.println("  /loop resume [id]           恢复循环");
        out.println("  /loop status                查看所有循环状态");
        out.println("  /loop help                  显示帮助");
        out.println();
        out.println("  interval 格式: 30s, 5m, 1h, 2h30m");
        out.println();
        out.println("  防失控保护（达到后自动停止，可在 loop_engineering 配置）:");
        out.println("    最大迭代: " + limitText(config.getLoopMaxIterations(), "步")
                + " | 超时: " + limitText(config.getLoopTimeoutMinutes(), "分钟")
                + " | 连续失败熔断: " + config.getLoopMaxConsecutiveFailures() + " 次");
        out.println();
        out.println("  示例:");
        out.println("    /loop 5m 检查编译状态并修复错误");
        out.println("    /loop 1h 扫描 TODO 注释并完成一个");
        out.println("    /loop stop 2");
        out.println();
    }

    // ==================== 工具方法 ====================

    /**
     * 解析时间间隔字符串
     * 支持: 30s, 5m, 1h, 2h30m, 1h30s 等
     */
    static Duration parseDuration(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        Matcher matcher = DURATION_PATTERN.matcher(input.trim().toLowerCase());
        if (!matcher.matches()) {
            return null;
        }

        long hours = matcher.group(1) != null ? Long.parseLong(matcher.group(1)) : 0;
        long minutes = matcher.group(2) != null ? Long.parseLong(matcher.group(2)) : 0;
        long seconds = matcher.group(3) != null ? Long.parseLong(matcher.group(3)) : 0;

        if (hours == 0 && minutes == 0 && seconds == 0) {
            return null;
        }

        return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds);
    }

    private String formatDuration(Duration duration) {
        if (duration == null) return "N/A";
        long totalSeconds = duration.getSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append("h");
        if (minutes > 0) sb.append(minutes).append("m");
        if (seconds > 0) sb.append(seconds).append("s");
        return sb.toString();
    }

    private String formatInstant(Instant instant) {
        return DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }

    /**
     * 限制值的展示文本（0 表示不限制）
     */
    private String limitText(int value, String unit) {
        return value > 0 ? value + " " + unit : "不限";
    }
}
