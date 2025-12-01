package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.graph.GraphManager;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * /graph 命令处理器
 * 管理代码图构建、查询和可视化
 * 
 * <p>子命令：
 * <ul>
 *   <li>/graph build [path] - 构建代码图</li>
 *   <li>/graph rebuild - 重新构建代码图</li>
 *   <li>/graph stats - 查看图统计信息</li>
 *   <li>/graph clear - 清空代码图</li>
 *   <li>/graph status - 查看图状态</li>
 * </ul>
 */
@Slf4j
@Component
public class GraphCommandHandler implements CommandHandler {
    
    @Autowired(required = false)
    private GraphManager graphManager;
    
    @Override
    public String getName() {
        return "graph";
    }
    
    @Override
    public String getDescription() {
        return "代码图管理";
    }
    
    @Override
    public List<String> getAliases() {
        return List.of("g");
    }
    
    @Override
    public String getUsage() {
        return "/graph <subcommand> [args]\n" +
               "  build [path]  - 构建代码图 (默认当前目录)\n" +
               "  rebuild       - 重新构建代码图\n" +
               "  stats         - 显示图统计信息\n" +
               "  clear         - 清空代码图\n" +
               "  status        - 显示图状态\n" +
               "  save          - 保存代码图到磁盘\n" +
               "  load          - 从磁盘加载代码图";
    }
    
    @Override
    public boolean isAvailable(CommandContext context) {
        return graphManager != null && graphManager.isEnabled();
    }
    
    @Override
    public void execute(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        // 检查图功能是否可用
        if (graphManager == null) {
            out.printError("代码图功能未初始化");
            return;
        }
        
        if (!graphManager.isEnabled()) {
            out.printError("代码图功能已禁用");
            out.printInfo("请在配置文件中启用: jimi.graph.enabled=true");
            return;
        }
        
        // 设置工作目录（从 Runtime 获取）
        if (context.getSoul() != null && context.getSoul().getRuntime() != null) {
            Path workDir = context.getSoul().getRuntime().getWorkDir();
            graphManager.setWorkDir(workDir);
        }
        
        // 解析子命令
        String[] args = context.getArgs();
        if (args.length == 0) {
            out.println();
            out.println(getUsage());
            out.println();
            return;
        }
        
        String subcommand = args[0].toLowerCase();
        
        try {
            switch (subcommand) {
                case "build":
                    handleBuild(context, args);
                    break;
                    
                case "rebuild":
                    handleRebuild(context);
                    break;
                    
                case "stats":
                    handleStats(context);
                    break;
                    
                case "clear":
                    handleClear(context);
                    break;
                    
                case "status":
                    handleStatus(context);
                    break;
                    
                case "save":
                    handleSave(context);
                    break;
                    
                case "load":
                    handleLoad(context);
                    break;
                    
                default:
                    out.printError("未知子命令: " + subcommand);
                    out.println();
                    out.println(getUsage());
                    out.println();
            }
        } catch (Exception e) {
            log.error("Error executing /graph " + subcommand, e);
            out.printError("执行失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理 build 子命令
     */
    private void handleBuild(CommandContext context, String[] args) {
        OutputFormatter out = context.getOutputFormatter();
        
        // 获取项目路径
        Path projectPath;
        if (args.length > 1) {
            projectPath = Paths.get(args[1]);
        } else {
            // 默认使用工作目录（从 Runtime 获取）
            if (context.getSoul() != null && context.getSoul().getRuntime() != null) {
                projectPath = context.getSoul().getRuntime().getWorkDir();
            } else {
                projectPath = Paths.get(System.getProperty("user.dir"));
            }
        }
        
        if (!projectPath.toFile().exists()) {
            out.printError("路径不存在: " + projectPath);
            return;
        }
        
        if (!projectPath.toFile().isDirectory()) {
            out.printError("不是目录: " + projectPath);
            return;
        }
        
        out.println();
        out.printInfo("开始构建代码图...");
        out.println("项目路径: " + projectPath.toAbsolutePath());
        out.println();
        
        long startTime = System.currentTimeMillis();
        
        try {
            GraphManager.BuildResult result = graphManager.buildGraph(projectPath).block();
            
            if (result == null) {
                out.printError("构建失败: 无返回结果");
                return;
            }
            
            if (!result.isSuccess()) {
                out.printError("构建失败: 代码图功能已禁用");
                return;
            }
            
            long duration = System.currentTimeMillis() - startTime;
            
            out.printSuccess("✅ 代码图构建完成");
            out.println();
            out.println("统计信息:");
            out.println("  实体数: " + result.getEntityCount());
            out.println("  关系数: " + result.getRelationCount());
            out.println("  耗时: " + duration + "ms");
            out.println();
            
        } catch (Exception e) {
            log.error("Failed to build graph", e);
            out.printError("构建失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理 rebuild 子命令
     */
    private void handleRebuild(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        if (!graphManager.isInitialized()) {
            out.printError("代码图尚未初始化，请先使用 /graph build 构建");
            return;
        }
        
        out.println();
        out.printInfo("重新构建代码图...");
        out.println();
        
        long startTime = System.currentTimeMillis();
        
        try {
            GraphManager.BuildResult result = graphManager.rebuildGraph().block();
            
            if (result == null) {
                out.printError("重建失败: 无返回结果");
                return;
            }
            
            long duration = System.currentTimeMillis() - startTime;
            
            out.printSuccess("✅ 代码图重建完成");
            out.println();
            out.println("统计信息:");
            out.println("  实体数: " + result.getEntityCount());
            out.println("  关系数: " + result.getRelationCount());
            out.println("  耗时: " + duration + "ms");
            out.println();
            
        } catch (Exception e) {
            log.error("Failed to rebuild graph", e);
            out.printError("重建失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理 stats 子命令
     */
    private void handleStats(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        try {
            GraphManager.GraphStats stats = graphManager.getGraphStats().block();
            
            if (stats == null) {
                out.printError("无法获取统计信息");
                return;
            }
            
            out.println();
            out.printSuccess("代码图统计:");
            out.println("  实体数: " + stats.getEntityCount());
            out.println("  关系数: " + stats.getRelationCount());
            out.println("  初始化状态: " + (stats.isInitialized() ? "已初始化" : "未初始化"));
            if (stats.getProjectRoot() != null) {
                out.println("  项目路径: " + stats.getProjectRoot());
            }
            out.println();
            
        } catch (Exception e) {
            log.error("Failed to get graph stats", e);
            out.printError("获取统计信息失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理 clear 子命令
     */
    private void handleClear(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        if (!graphManager.isInitialized()) {
            out.printInfo("代码图已经为空");
            return;
        }
        
        try {
            graphManager.clearGraph();
            out.printSuccess("✅ 代码图已清空");
            
        } catch (Exception e) {
            log.error("Failed to clear graph", e);
            out.printError("清空失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理 status 子命令
     */
    private void handleStatus(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        out.println();
        out.printSuccess("代码图状态:");
        out.println("  启用状态: " + (graphManager.isEnabled() ? "已启用" : "已禁用"));
        out.println("  初始化状态: " + (graphManager.isInitialized() ? "已初始化" : "未初始化"));
        
        try {
            GraphManager.GraphStats stats = graphManager.getGraphStats().block();
            if (stats != null && stats.isInitialized()) {
                out.println("  实体数: " + stats.getEntityCount());
                out.println("  关系数: " + stats.getRelationCount());
                if (stats.getProjectRoot() != null) {
                    out.println("  项目路径: " + stats.getProjectRoot());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get detailed stats", e);
        }
        
        out.println();
    }
    
    /**
     * 处理 save 子命令
     */
    private void handleSave(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        if (!graphManager.isInitialized()) {
            out.printError("代码图尚未初始化，无法保存");
            return;
        }
        
        out.println();
        out.printInfo("保存代码图...");
        out.println();
        
        try {
            Boolean success = graphManager.saveGraph().block();
            
            if (success != null && success) {
                out.printSuccess("✅ 代码图已保存");
            } else {
                out.printError("保存失败");
            }
            out.println();
            
        } catch (Exception e) {
            log.error("Failed to save graph", e);
            out.printError("保存失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理 load 子命令
     */
    private void handleLoad(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        out.println();
        out.printInfo("加载代码图...");
        out.println();
        
        try {
            Boolean success = graphManager.loadGraph().block();
            
            if (success != null && success) {
                out.printSuccess("✅ 代码图已加载");
                
                // 显示统计信息
                GraphManager.GraphStats stats = graphManager.getGraphStats().block();
                if (stats != null) {
                    out.println();
                    out.println("统计信息:");
                    out.println("  实体数: " + stats.getEntityCount());
                    out.println("  关系数: " + stats.getRelationCount());
                }
            } else {
                out.printError("加载失败: 未找到已保存的代码图");
                out.printInfo("提示: 请先使用 /graph build 构建代码图");
            }
            out.println();
            
        } catch (Exception e) {
            log.error("Failed to load graph", e);
            out.printError("加载失败: " + e.getMessage());
        }
    }
}
