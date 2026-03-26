package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.core.async.AsyncSubagent;
import io.leavesfly.jimi.core.async.AsyncSubagentManager;
import io.leavesfly.jimi.core.async.AsyncSubagentPersistence;
import io.leavesfly.jimi.core.async.AsyncSubagentRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 异步子代理管理命令处理器
 * 
 * 提供以下子命令：
 * - /async list     - 列出所有活跃的异步子代理
 * - /async status   - 查看指定子代理状态
 * - /async cancel   - 取消指定子代理
 * - /async result   - 获取已完成子代理的结果
 * - /async history  - 查看最近完成的子代理
 * 
 * @author Jimi
 */
@Slf4j
@Component
public class AsyncCommandHandler implements CommandHandler {
    
    private static final DateTimeFormatter TIME_FORMATTER = 
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    
    @Autowired
    private AsyncSubagentManager asyncSubagentManager;
    
    @Autowired
    private AsyncSubagentPersistence persistence;
    
    @Override
    public String getName() {
        return "async";
    }
    
    @Override
    public String getDescription() {
        return "管理异步子代理";
    }
    
    @Override
    public String getUsage() {
        return """
                /async list              列出所有活跃的异步子代理
                /async status <id>       查看指定子代理状态
                /async cancel <id>       取消指定子代理
                /async result <id>       获取已完成子代理的结果
                /async history [count]   查看历史记录（默认10条）
                /async history clear     清理历史记录""";
    }
    
    @Override
    public List<String> getAliases() {
        return List.of("bg");  // 别名 /bg
    }
    
    @Override
    public String getCategory() {
        return "agent";
    }
    
    @Override
    public void execute(CommandContext context) throws Exception {
        String[] args = context.getArgs();
        
        if (args == null || args.length == 0) {
            printUsage(context);
            return;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "list", "ls" -> handleList(context);
            case "status", "s" -> handleStatus(context, args);
            case "cancel", "c" -> handleCancel(context, args);
            case "result", "r" -> handleResult(context, args);
            case "history", "h" -> handleHistory(context, args);
            default -> {
                context.getOutputFormatter().printWarning("未知子命令: " + subCommand);
                printUsage(context);
            }
        }
    }
    
    /**
     * 列出活跃的异步子代理
     */
    private void handleList(CommandContext context) {
        List<AsyncSubagent> active = asyncSubagentManager.listActive();
        
        if (active.isEmpty()) {
            context.getOutputFormatter().printInfo("当前没有活跃的异步子代理");
            context.getOutputFormatter().printInfo("使用 AsyncTask 工具启动异步子代理");
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("活跃的异步子代理 (").append(active.size()).append("个):\n\n");
        
        for (AsyncSubagent subagent : active) {
            Duration running = subagent.getRunningDuration();
            String startTime = TIME_FORMATTER.format(subagent.getStartTime());
            
            sb.append(String.format("  [%s] %s\n", subagent.getId(), subagent.getName()));
            sb.append(String.format("       状态: %s | 模式: %s\n", 
                    subagent.getStatus().getDisplayName(),
                    subagent.getMode().getDisplayName()));
            sb.append(String.format("       启动: %s | 运行: %ds\n", startTime, running.getSeconds()));
            sb.append("\n");
        }
        
        sb.append("提示: 使用 /async status <id> 查看详情，/async cancel <id> 取消执行");
        context.getOutputFormatter().printInfo(sb.toString());
    }
    
    /**
     * 查看子代理状态
     */
    private void handleStatus(CommandContext context, String[] args) {
        if (args.length < 2) {
            context.getOutputFormatter().printWarning("用法: /async status <id>");
            return;
        }
        
        String id = args[1];
        asyncSubagentManager.getSubagent(id).ifPresentOrElse(
                subagent -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("子代理详情:\n\n");
                    sb.append(String.format("  ID:       %s\n", subagent.getId()));
                    sb.append(String.format("  名称:     %s\n", subagent.getName()));
                    sb.append(String.format("  状态:     %s\n", subagent.getStatus().getDisplayName()));
                    sb.append(String.format("  模式:     %s\n", subagent.getMode().getDisplayName()));
                    sb.append(String.format("  启动时间: %s\n", 
                            TIME_FORMATTER.format(subagent.getStartTime())));
                    
                    if (subagent.getEndTime() != null) {
                        sb.append(String.format("  结束时间: %s\n", 
                                TIME_FORMATTER.format(subagent.getEndTime())));
                        sb.append(String.format("  运行时长: %ds\n", 
                                subagent.getRunningDuration().getSeconds()));
                    } else {
                        sb.append(String.format("  运行时长: %ds (进行中)\n", 
                                subagent.getRunningDuration().getSeconds()));
                    }
                    
                    if (subagent.getTimeout() != null) {
                        sb.append(String.format("  超时设置: %ds\n", subagent.getTimeout().getSeconds()));
                    }
                    
                    if (subagent.getPrompt() != null) {
                        String prompt = subagent.getPrompt();
                        if (prompt.length() > 100) {
                            prompt = prompt.substring(0, 100) + "...";
                        }
                        sb.append(String.format("  任务提示: %s\n", prompt));
                    }
                    
                    if (subagent.getError() != null) {
                        sb.append(String.format("  错误信息: %s\n", subagent.getError().getMessage()));
                    }
                    
                    context.getOutputFormatter().printInfo(sb.toString());
                },
                () -> context.getOutputFormatter().printWarning("未找到子代理: " + id)
        );
    }
    
    /**
     * 取消子代理
     */
    private void handleCancel(CommandContext context, String[] args) {
        if (args.length < 2) {
            context.getOutputFormatter().printWarning("用法: /async cancel <id>");
            return;
        }
        
        String id = args[1];
        boolean cancelled = asyncSubagentManager.cancel(id);
        
        if (cancelled) {
            context.getOutputFormatter().printSuccess("已取消子代理: " + id);
        } else {
            context.getOutputFormatter().printWarning("无法取消子代理: " + id + " (可能已完成或不存在)");
        }
    }
    
    /**
     * 获取子代理结果
     */
    private void handleResult(CommandContext context, String[] args) {
        if (args.length < 2) {
            context.getOutputFormatter().printWarning("用法: /async result <id>");
            return;
        }
        
        String id = args[1];
        asyncSubagentManager.getSubagent(id).ifPresentOrElse(
                subagent -> {
                    if (!subagent.isCompleted()) {
                        context.getOutputFormatter().printWarning(
                                "子代理尚未完成，当前状态: " + subagent.getStatus().getDisplayName());
                        return;
                    }
                    
                    StringBuilder sb = new StringBuilder();
                    sb.append("子代理执行结果:\n\n");
                    sb.append(String.format("ID: %s | 状态: %s | 用时: %ds\n\n",
                            subagent.getId(),
                            subagent.getStatus().getDisplayName(),
                            subagent.getRunningDuration().getSeconds()));
                    
                    if (subagent.isSuccess()) {
                        sb.append("--- 结果 ---\n");
                        sb.append(subagent.getResult() != null ? subagent.getResult() : "(无结果)");
                    } else {
                        sb.append("--- 错误 ---\n");
                        sb.append(subagent.getError() != null 
                                ? subagent.getError().getMessage() 
                                : "(未知错误)");
                    }
                    
                    context.getOutputFormatter().printInfo(sb.toString());
                },
                () -> context.getOutputFormatter().printWarning("未找到子代理: " + id)
        );
    }
    
    /**
     * 查看历史记录
     */
    private void handleHistory(CommandContext context, String[] args) {
        Path workDir = context.getSoul().getRuntime().getSession().getWorkDir();
        
        // 检查是否有清理参数
        if (args.length >= 2 && "clear".equalsIgnoreCase(args[1])) {
            int cleared = persistence.clearHistory(workDir);
            if (cleared > 0) {
                context.getOutputFormatter().printSuccess("已清理 " + cleared + " 条历史记录");
            } else {
                context.getOutputFormatter().printInfo("没有历史记录需要清理");
            }
            return;
        }
        
        // 解析数量参数
        int limit = 10;
        if (args.length >= 2) {
            try {
                limit = Integer.parseInt(args[1]);
                if (limit <= 0) limit = 10;
                if (limit > 100) limit = 100;
            } catch (NumberFormatException e) {
                // 忽略，使用默认值
            }
        }
        
        // 先显示内存中的已完成记录
        List<AsyncSubagent> completed = asyncSubagentManager.listCompleted();
        
        // 再加载持久化记录
        List<AsyncSubagentRecord> persisted = persistence.getHistory(workDir, limit);
        
        if (completed.isEmpty() && persisted.isEmpty()) {
            context.getOutputFormatter().printInfo("没有异步子代理历史记录");
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        
        // 内存中的已完成记录（本次会话）
        if (!completed.isEmpty()) {
            sb.append("本次会话完成的子代理 (").append(completed.size()).append("个):\n\n");
            for (AsyncSubagent subagent : completed) {
                String statusIcon = subagent.isSuccess() ? "✓" : "✗";
                sb.append(String.format("  %s [%s] %s - %s (%ds)\n",
                        statusIcon,
                        subagent.getId(),
                        subagent.getName(),
                        subagent.getStatus().getDisplayName(),
                        subagent.getRunningDuration().getSeconds()));
            }
            sb.append("\n");
        }
        
        // 持久化记录
        if (!persisted.isEmpty()) {
            sb.append("持久化历史记录 (\u6700近").append(persisted.size()).append("条):\n\n");
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());
            
            for (AsyncSubagentRecord record : persisted) {
                String statusIcon = "completed".equals(record.getStatus()) ? "✓" : "✗";
                String timeStr = record.getStartTime() != null 
                        ? dateFormatter.format(record.getStartTime()) 
                        : "--";
                sb.append(String.format("  %s [%s] %s - %s (%s) %s\n",
                        statusIcon,
                        record.getId(),
                        record.getName() != null ? record.getName() : "unknown",
                        record.getStatus(),
                        record.getFormattedDuration(),
                        timeStr));
            }
        }
        
        sb.append("\n提示: 使用 /async result <id> 查看详细结果\n");
        sb.append("      使用 /async history clear 清理历史记录");
        context.getOutputFormatter().printInfo(sb.toString());
    }
    
    /**
     * 打印用法说明
     */
    private void printUsage(CommandContext context) {
        context.getOutputFormatter().printInfo(getUsage());
    }
}
