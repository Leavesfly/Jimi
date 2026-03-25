package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.knowledge.domain.query.RetrievalQuery;
import io.leavesfly.jimi.knowledge.domain.result.RetrievalResult;
import io.leavesfly.jimi.knowledge.rag.RagManager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 索引管理命令处理器
 * <p>
 * 支持的命令：
 * - /index build [path]    : 构建索引
 * - /index query <text>    : 查询索引
 * - /index stats           : 查看索引统计
 * - /index clear           : 清空索引
 * <p>
 * 示例：
 * /index build src/main/java
 * /index query "如何处理用户认证"
 * /index stats
 */
@Slf4j
@Component
public class IndexCommandHandler implements CommandHandler {

    @Autowired(required = false)
    private RagManager ragManager;

    @Override
    public String getName() {
        return "index";
    }

    @Override
    public String getDescription() {
        return "向量索引管理 - 支持: build/query/stats/clear";
    }

    @Override
    public String getCategory() {
        return "上下文管理";
    }

    @Override
    public void execute(CommandContext context) {
        String[] args = context.getArgs();
        
        if (ragManager == null || !ragManager.isEnabled()) {
            context.getOutputFormatter().printWarning("向量索引未启用（RagManager未配置）");
            return;
        }

        if (args.length == 0) {
            printUsage(context);
            return;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "build":
                handleBuild(context, args);
                break;
            case "query":
                handleQuery(context, args);
                break;
            case "stats":
                handleStats(context);
                break;
            case "clear":
                if (args.length > 1 && "--confirm".equals(args[1])) {
                    handleClearConfirmed(context);
                } else {
                    handleClear(context);
                }
                break;
            default:
                context.getOutputFormatter().printError("未知子命令: " + subCommand);
                printUsage(context);
        }
    }

    private void handleBuild(CommandContext context, String[] args) {
        // 解析参数
        String targetPath = args.length > 1 ? args[1] : 
                (context.getSoul() != null && context.getSoul().getRuntime() != null 
                        ? context.getSoul().getRuntime().getWorkDir().toString() : ".");
    
        context.getOutputFormatter().printInfo("🔨 开始构建索引...");
        context.getOutputFormatter().printInfo("   目标路径: " + targetPath);
    
        try {
            Path basePath = Paths.get(targetPath).toAbsolutePath();
            if (!Files.exists(basePath)) {
                context.getOutputFormatter().printError("路径不存在: " + basePath);
                return;
            }
    
            // 通过 RagManager 构建索引
            RetrievalResult result = ragManager.buildIndex(basePath).block();
            
            if (result == null || !result.isSuccess()) {
                context.getOutputFormatter().printError("构建失败: " + 
                        (result != null ? result.getErrorMessage() : "未知错误"));
                return;
            }
    
            // 保存索引
            Boolean saved = ragManager.save().block();
            if (Boolean.TRUE.equals(saved)) {
                context.getOutputFormatter().printSuccess("✅ 索引已保存");
            }
    
            int totalChunks = result.getIndexStats() != null ? 
                    result.getIndexStats().getTotalChunks() : 0;
            context.getOutputFormatter().printSuccess("✅ 构建完成: " + totalChunks + " 个片段, 耗时: " + 
                    result.getElapsedMs() + "ms");
    
        } catch (Exception e) {
            log.error("构建索引失败", e);
            context.getOutputFormatter().printError("构建失败: " + e.getMessage());
        }
    }

    private void handleQuery(CommandContext context, String[] args) {
        if (args.length < 2) {
            context.getOutputFormatter().printError("缺少查询文本");
            context.getOutputFormatter().printInfo("   用法: /index query <查询文本>");
            return;
        }

        // 拼接查询文本（从第2个参数开始）
        String queryText = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        
        context.getOutputFormatter().printInfo("🔍 查询索引: " + queryText);

        try {
            RetrievalQuery query = RetrievalQuery.builder()
                    .query(queryText)
                    .topK(5)
                    .includeContent(true)
                    .build();
            
            RetrievalResult result = ragManager.retrieve(query).block();

            if (result == null || !result.isSuccess()) {
                context.getOutputFormatter().printError("查询失败: " + 
                        (result != null ? result.getErrorMessage() : "未知错误"));
                return;
            }
            
            if (result.getChunks() == null || result.getChunks().isEmpty()) {
                context.getOutputFormatter().printWarning("没有找到相关结果");
                return;
            }

            context.getOutputFormatter().printInfo("\n找到 " + result.getChunks().size() + " 个相关片段（耗时: " + 
                    result.getElapsedMs() + "ms）：");
            
            int index = 1;
            for (RetrievalResult.CodeChunkResult chunk : result.getChunks()) {
                context.getOutputFormatter().printInfo("\n" + index + ". " + chunk.getFilePath() + 
                        ":" + chunk.getStartLine() + "-" + chunk.getEndLine() +
                        " (score: " + String.format("%.3f", chunk.getScore()) + ")");
                String preview = chunk.getContent();
                if (preview != null && preview.length() > 100) {
                    preview = preview.substring(0, 100) + "...";
                }
                context.getOutputFormatter().printInfo("   预览: " + (preview != null ? preview : "(无内容)"));
                index++;
            }

        } catch (Exception e) {
            log.error("查询索引失败", e);
            context.getOutputFormatter().printError("查询失败: " + e.getMessage());
        }
    }

    private void handleStats(CommandContext context) {
        context.getOutputFormatter().printInfo("📊 获取索引统计...");
        
        try {
            RetrievalResult.IndexStats stats = ragManager.getStats().block();
            
            if (stats == null) {
                context.getOutputFormatter().printWarning("无法获取统计信息");
                return;
            }
            
            context.getOutputFormatter().printInfo("\n索引统计信息:");
            context.getOutputFormatter().printInfo("  片段总数: " + stats.getTotalChunks());
            context.getOutputFormatter().printInfo("  文件总数: " + stats.getTotalFiles());
            context.getOutputFormatter().printInfo("  索引大小: " + formatBytes(stats.getIndexSizeBytes()));
            if (stats.getLastUpdated() > 0) {
                context.getOutputFormatter().printInfo("  最后更新: " + 
                    new java.util.Date(stats.getLastUpdated()));
            }
        } catch (Exception e) {
            log.error("获取索引统计失败", e);
            context.getOutputFormatter().printError("获取统计信息失败: " + e.getMessage());
        }
    }

    private void handleClear(CommandContext context) {
        context.getOutputFormatter().printWarning("⚠️  清空索引将删除所有片段，此操作不可恢复！");
        context.getOutputFormatter().printInfo("请重新输入命令确认: /index clear --confirm");
    }
    
    private void handleClearConfirmed(CommandContext context) {
        context.getOutputFormatter().printInfo("🗑️  正在清空索引...");
        
        try {
            // 获取当前统计
            RetrievalResult.IndexStats statsBefore = ragManager.getStats().block();
            
            // 清空索引
            ragManager.clear().block();
            
            // 保存空索引
            ragManager.save().block();
            
            context.getOutputFormatter().printSuccess(
                String.format("✅ 索引已清空（删除了 %d 个片段）", 
                    statsBefore != null ? statsBefore.getTotalChunks() : 0));
                    
        } catch (Exception e) {
            log.error("清空索引失败", e);
            context.getOutputFormatter().printError("清空失败: " + e.getMessage());
        }
    }

    private void printUsage(CommandContext context) {
        context.getOutputFormatter().printInfo("\n📚 索引管理命令用法:");
        context.getOutputFormatter().printInfo("  /index build [path]");
        context.getOutputFormatter().printInfo("      构建索引（path默认为当前工作目录）");
        context.getOutputFormatter().printInfo("  /index query <查询文本>");
        context.getOutputFormatter().printInfo("      查询索引并预览结果");
        context.getOutputFormatter().printInfo("  /index stats");
        context.getOutputFormatter().printInfo("      查看索引统计信息");
        context.getOutputFormatter().printInfo("  /index clear");
        context.getOutputFormatter().printInfo("      清空索引");
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
