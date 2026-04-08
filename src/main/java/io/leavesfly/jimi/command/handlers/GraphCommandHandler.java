package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.knowledge.graph.GraphManager;
import io.leavesfly.jimi.knowledge.graph.GraphSearchEngine;
import io.leavesfly.jimi.knowledge.graph.model.CodeEntity;
import io.leavesfly.jimi.knowledge.graph.model.EntityType;
import io.leavesfly.jimi.knowledge.graph.model.RelationType;
import io.leavesfly.jimi.knowledge.graph.navigator.GraphNavigator;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
 *   <li>/graph query {type} {query} - 查询代码图</li>
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
               "  load          - 从磁盘加载代码图\n" +
               "  query <type> <query> - 查询代码图\n" +
               "    query symbol <name> [-t type]  - 按符号名称查询\n" +
               "    query file <path>              - 按文件路径查询\n" +
               "    query callers <methodId>       - 查找方法调用者\n" +
               "    query callees <methodId>       - 查找方法被调用者";
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
        
        // 设置工作目录（从 EngineClient 获取）
        graphManager.setWorkDir(context.getEngineClient().getWorkDir());
        
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
                    
                case "query":
                    handleQuery(context, args);
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
            // 默认使用工作目录（从 EngineClient 获取）
            projectPath = context.getEngineClient().getWorkDir();
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

    /**
     * 处理 query 子命令
     */
    private void handleQuery(CommandContext context, String[] args) {
        OutputFormatter out = context.getOutputFormatter();
        
        if (!graphManager.isInitialized()) {
            out.printError("代码图尚未初始化，请先使用 /graph build 构建");
            return;
        }
        
        if (args.length < 2) {
            out.printError("请指定查询类型");
            out.println();
            out.println("查询类型:");
            out.println("  symbol <name> [-t type]  - 按符号名称查询");
            out.println("  file <path>              - 按文件路径查询");
            out.println("  callers <methodId>       - 查找方法调用者");
            out.println("  callees <methodId>       - 查找方法被调用者");
            out.println();
            return;
        }
        
        String queryType = args[1].toLowerCase();
        
        switch (queryType) {
            case "symbol":
                handleSymbolQuery(context, args);
                break;
            case "file":
                handleFileQuery(context, args);
                break;
            case "callers":
                handleCallersQuery(context, args);
                break;
            case "callees":
                handleCalleesQuery(context, args);
                break;
            default:
                out.printError("未知查询类型: " + queryType);
                out.println("支持的查询类型: symbol, file, callers, callees");
        }
    }

    /**
     * 处理符号查询
     * 用法: /graph query symbol <name> [-t type]
     */
    private void handleSymbolQuery(CommandContext context, String[] args) {
        OutputFormatter out = context.getOutputFormatter();
        
        if (args.length < 3) {
            out.printError("请指定符号名称");
            out.println("用法: /graph query symbol <name> [-t type]");
            out.println("示例: /graph query symbol UserService -t class");
            return;
        }
        
        String symbolName = args[2];
        Set<EntityType> entityTypes = null;
        int limit = 20;
        
        // 解析可选参数
        for (int i = 3; i < args.length; i++) {
            if ("-t".equals(args[i]) && i + 1 < args.length) {
                try {
                    EntityType type = EntityType.valueOf(args[i + 1].toUpperCase());
                    entityTypes = Set.of(type);
                } catch (IllegalArgumentException e) {
                    out.printError("无效的实体类型: " + args[i + 1]);
                    out.println("可用类型: " + Arrays.toString(EntityType.values()));
                    return;
                }
                i++;
            } else if ("-l".equals(args[i]) && i + 1 < args.length) {
                try {
                    limit = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    out.printError("无效的数量限制: " + args[i + 1]);
                    return;
                }
                i++;
            }
        }
        
        out.println();
        out.printInfo("符号查询: " + symbolName);
        if (entityTypes != null) {
            out.println("类型过滤: " + entityTypes);
        }
        out.println();
        
        try {
            GraphSearchEngine searchEngine = graphManager.getSearchEngine();
            GraphSearchEngine.GraphSearchResult result = searchEngine
                    .searchBySymbol(symbolName, entityTypes, limit)
                    .block();
            
            if (result == null || !result.getSuccess()) {
                out.printError("查询失败: " + (result != null ? result.getErrorMessage() : "无结果"));
                return;
            }
            
            List<GraphSearchEngine.ScoredEntity> results = result.getResults();
            if (results.isEmpty()) {
                out.printInfo("未找到匹配的符号");
                return;
            }
            
            out.printSuccess("找到 " + result.getTotalResults() + " 个结果 (耗时: " + result.getElapsedMs() + "ms)");
            out.println();
            
            for (GraphSearchEngine.ScoredEntity scored : results) {
                CodeEntity entity = scored.getEntity();
                out.println(String.format("  [%s] %s", entity.getType(), entity.getQualifiedName()));
                out.println(String.format("    文件: %s", entity.getFilePath()));
                out.println(String.format("    匹配: %s (分数: %.2f)", scored.getReason(), scored.getScore()));
                out.println();
            }
            
        } catch (Exception e) {
            log.error("Symbol query failed", e);
            out.printError("查询失败: " + e.getMessage());
        }
    }

    /**
     * 处理文件查询
     * 用法: /graph query file <path>
     */
    private void handleFileQuery(CommandContext context, String[] args) {
        OutputFormatter out = context.getOutputFormatter();
        
        if (args.length < 3) {
            out.printError("请指定文件路径");
            out.println("用法: /graph query file <path>");
            return;
        }
        
        String filePath = args[2];
        int limit = 50;
        
        if (args.length > 3) {
            try {
                limit = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                // 使用默认值
            }
        }
        
        out.println();
        out.printInfo("文件查询: " + filePath);
        out.println();
        
        try {
            GraphSearchEngine searchEngine = graphManager.getSearchEngine();
            GraphSearchEngine.GraphSearchResult result = searchEngine
                    .searchByFile(filePath, limit)
                    .block();
            
            if (result == null || !result.getSuccess()) {
                out.printError("查询失败: " + (result != null ? result.getErrorMessage() : "无结果"));
                return;
            }
            
            List<GraphSearchEngine.ScoredEntity> results = result.getResults();
            if (results.isEmpty()) {
                out.printInfo("未找到匹配的文件");
                return;
            }
            
            out.printSuccess("找到 " + result.getTotalResults() + " 个结果 (耗时: " + result.getElapsedMs() + "ms)");
            out.println();
            
            // 按类型分组显示
            java.util.Map<EntityType, List<GraphSearchEngine.ScoredEntity>> grouped = results.stream()
                    .collect(Collectors.groupingBy(se -> se.getEntity().getType()));
            
            for (java.util.Map.Entry<EntityType, List<GraphSearchEngine.ScoredEntity>> entry : grouped.entrySet()) {
                out.println("[" + entry.getKey() + "] (" + entry.getValue().size() + "个)");
                for (GraphSearchEngine.ScoredEntity scored : entry.getValue()) {
                    CodeEntity entity = scored.getEntity();
                    out.println("  - " + entity.getName() + " (" + entity.getQualifiedName() + ")");
                }
                out.println();
            }
            
        } catch (Exception e) {
            log.error("File query failed", e);
            out.printError("查询失败: " + e.getMessage());
        }
    }

    /**
     * 处理调用者查询
     * 用法: /graph query callers <methodId>
     */
    private void handleCallersQuery(CommandContext context, String[] args) {
        OutputFormatter out = context.getOutputFormatter();
        
        if (args.length < 3) {
            out.printError("请指定方法ID");
            out.println("用法: /graph query callers <methodId>");
            out.println("提示: methodId 可以通过 symbol 查询获取");
            return;
        }
        
        String methodId = args[2];
        int maxDepth = 3;
        
        if (args.length > 3) {
            try {
                maxDepth = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                // 使用默认值
            }
        }
        
        out.println();
        out.printInfo("查找调用者: " + methodId);
        out.println("最大深度: " + maxDepth);
        out.println();
        
        try {
            GraphNavigator navigator = graphManager.getNavigator();
            List<CodeEntity> callers = navigator.findCallers(methodId, maxDepth).block();
            
            if (callers == null || callers.isEmpty()) {
                out.printInfo("未找到调用者");
                return;
            }
            
            out.printSuccess("找到 " + callers.size() + " 个调用者");
            out.println();
            
            for (CodeEntity caller : callers) {
                out.println(String.format("  [%s] %s", caller.getType(), caller.getQualifiedName()));
                out.println(String.format("    文件: %s:%d", caller.getFilePath(), caller.getStartLine()));
                out.println();
            }
            
        } catch (Exception e) {
            log.error("Callers query failed", e);
            out.printError("查询失败: " + e.getMessage());
        }
    }

    /**
     * 处理被调用者查询
     * 用法: /graph query callees <methodId>
     */
    private void handleCalleesQuery(CommandContext context, String[] args) {
        OutputFormatter out = context.getOutputFormatter();
        
        if (args.length < 3) {
            out.printError("请指定方法ID");
            out.println("用法: /graph query callees <methodId>");
            out.println("提示: methodId 可以通过 symbol 查询获取");
            return;
        }
        
        String methodId = args[2];
        int maxDepth = 3;
        
        if (args.length > 3) {
            try {
                maxDepth = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                // 使用默认值
            }
        }
        
        out.println();
        out.printInfo("查找被调用方法: " + methodId);
        out.println("最大深度: " + maxDepth);
        out.println();
        
        try {
            GraphNavigator navigator = graphManager.getNavigator();
            List<CodeEntity> callees = navigator.findCallees(methodId, maxDepth).block();
            
            if (callees == null || callees.isEmpty()) {
                out.printInfo("未找到被调用方法");
                return;
            }
            
            out.printSuccess("找到 " + callees.size() + " 个被调用方法");
            out.println();
            
            for (CodeEntity callee : callees) {
                out.println(String.format("  [%s] %s", callee.getType(), callee.getQualifiedName()));
                out.println(String.format("    文件: %s:%d", callee.getFilePath(), callee.getStartLine()));
                out.println();
            }
            
        } catch (Exception e) {
            log.error("Callees query failed", e);
            out.printError("查询失败: " + e.getMessage());
        }
    }
}