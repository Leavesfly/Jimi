package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.config.VectorIndexConfig;
import io.leavesfly.jimi.retrieval.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 索引管理命令处理器
 * <p>
 * 支持的命令：
 * - /index build [path] [options]  : 构建索引
 * - /index update [path]           : 增量更新索引
 * - /index query <text>            : 查询索引
 * - /index stats                   : 查看索引统计
 * - /index clear                   : 清空索引
 * <p>
 * 示例：
 * /index build src/main/java --chunk-size=50
 * /index query "如何处理用户认证"
 * /index stats
 */
@Slf4j
@Component
public class IndexCommandHandler implements CommandHandler {

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired(required = false)
    private EmbeddingProvider embeddingProvider;

    @Autowired(required = false)
    private Chunker chunker;

    @Autowired(required = false)
    private RetrievalPipeline retrievalPipeline;

    @Autowired(required = false)
    private VectorIndexConfig vectorIndexConfig;

    @Override
    public String getName() {
        return "index";
    }

    @Override
    public String getDescription() {
        return "向量索引管理 - 支持: build/update/query/stats/clear";
    }

    @Override
    public String getCategory() {
        return "上下文管理";
    }

    @Override
    public void execute(CommandContext context) {
        String[] args = context.getArgs();
        
        if (vectorStore == null) {
            context.getOutputFormatter().printWarning("向量索引未启用（VectorStore未配置）");
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
            case "update":
                handleUpdate(context, args);
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
        if (vectorStore == null || chunker == null || embeddingProvider == null) {
            context.getOutputFormatter().printError("索引组件未启用，请检查配置");
            return;
        }
    
        // 解析参数
        String targetPath = args.length > 1 ? args[1] : ".";
        int chunkSize = vectorIndexConfig != null ? vectorIndexConfig.getChunkSize() : 50;
        int overlap = vectorIndexConfig != null ? vectorIndexConfig.getChunkOverlap() : 5;
    
        // 解析可选参数
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("--chunk-size=")) {
                chunkSize = Integer.parseInt(args[i].substring("--chunk-size=".length()));
            } else if (args[i].startsWith("--overlap=")) {
                overlap = Integer.parseInt(args[i].substring("--overlap=".length()));
            }
        }
    
        context.getOutputFormatter().printInfo("🔨 开始构庻索引...");
        context.getOutputFormatter().printInfo("   目标路径: " + targetPath);
        context.getOutputFormatter().printInfo("   分块大小: " + chunkSize + " 行");
        context.getOutputFormatter().printInfo("   重叠大小: " + overlap + " 行");
    
        try {
            Path basePath = Paths.get(targetPath).toAbsolutePath();
            if (!Files.exists(basePath)) {
                context.getOutputFormatter().printError("路径不存在: " + basePath);
                return;
            }
    
            // 扫描文件
            List<Path> sourceFiles = scanSourceFiles(basePath);
            context.getOutputFormatter().printInfo("📂 找到 " + sourceFiles.size() + " 个源文件");
    
            if (sourceFiles.isEmpty()) {
                context.getOutputFormatter().printWarning("没有找到源文件");
                return;
            }
    
            // 分块并生成向量
            int totalChunks = 0;
            for (Path file : sourceFiles) {
                String content = Files.readString(file);
                String relativePath = basePath.relativize(file).toString();
    
                List<CodeChunk> chunks = chunker.chunk(relativePath, content, chunkSize, overlap)
                        .collectList()
                        .block();
    
                if (chunks == null || chunks.isEmpty()) {
                    continue;
                }
    
                // 生成向量
                for (CodeChunk chunk : chunks) {
                    float[] embedding = embeddingProvider.embed(chunk.getContent()).block();
                    chunk.setEmbedding(embedding);
                }
    
                // 添加到索引
                int added = vectorStore.addBatch(chunks).block();
                totalChunks += added;
                
                // 更新MD5缓存
                if (vectorStore instanceof InMemoryVectorStore) {
                    InMemoryVectorStore inMemoryStore = (InMemoryVectorStore) vectorStore;
                    String md5 = calculateMD5(content);
                    inMemoryStore.updateFileMD5(relativePath, md5);
                }
    
                context.getOutputFormatter().printInfo("✓ " + relativePath + ": " + chunks.size() + "个片段");
            }
    
            // 保存索引
            if (vectorIndexConfig != null) {
                Path indexPath = Paths.get(vectorIndexConfig.getIndexPath());
                boolean saved = vectorStore.save().block();
                if (saved) {
                    context.getOutputFormatter().printSuccess("✅ 索引已保存: " + indexPath);
                }
            }
    
            context.getOutputFormatter().printSuccess("✅ 构庻完成: " + totalChunks + "个片段");
    
        } catch (Exception e) {
            log.error("构庻索引失败", e);
            context.getOutputFormatter().printError("构庻失败: " + e.getMessage());
        }
    }

    private void handleUpdate(CommandContext context, String[] args) {
        if (vectorStore == null || chunker == null || embeddingProvider == null) {
            context.getOutputFormatter().printError("索引组件未启用，请检查配置");
            return;
        }

        String targetPath = args.length > 1 ? args[1] : ".";
        
        context.getOutputFormatter().printInfo("🔄 开始增量更新索引...");
        context.getOutputFormatter().printInfo("   目标路径: " + targetPath);

        try {
            Path basePath = Paths.get(targetPath).toAbsolutePath();
            if (!Files.exists(basePath)) {
                context.getOutputFormatter().printError("路径不存在: " + basePath);
                return;
            }

            // 扫描文件
            List<Path> sourceFiles = scanSourceFiles(basePath);
            context.getOutputFormatter().printInfo("📂 找到 " + sourceFiles.size() + " 个源文件");

            int chunkSize = vectorIndexConfig != null ? vectorIndexConfig.getChunkSize() : 50;
            int overlap = vectorIndexConfig != null ? vectorIndexConfig.getChunkOverlap() : 5;
            
            int updated = 0;
            int added = 0;
            int skipped = 0;

            for (Path file : sourceFiles) {
                String content = Files.readString(file);
                String relativePath = basePath.relativize(file).toString();

                // 计算文件MD5
                String currentMD5 = calculateMD5(content);

                // 检查是否需要更新（使用VectorStore的MD5缓存）
                boolean needsUpdate = false;
                if (vectorStore instanceof InMemoryVectorStore) {
                    InMemoryVectorStore inMemoryStore = (InMemoryVectorStore) vectorStore;
                    needsUpdate = inMemoryStore.fileNeedsUpdate(relativePath, currentMD5);
                } else {
                    needsUpdate = true;
                }
                
                if (!needsUpdate) {
                    skipped++;
                    continue;
                }

                // 删除旧片段
                int deleted = vectorStore.deleteByFilePath(relativePath).block();
                if (deleted > 0) {
                    updated++;
                } else {
                    added++;
                }

                // 重新分块和向量化
                List<CodeChunk> chunks = chunker.chunk(relativePath, content, chunkSize, overlap)
                        .collectList()
                        .block();

                if (chunks != null && !chunks.isEmpty()) {
                    for (CodeChunk chunk : chunks) {
                        float[] embedding = embeddingProvider.embed(chunk.getContent()).block();
                        chunk.setEmbedding(embedding);
                    }
                    vectorStore.addBatch(chunks).block();
                }
                
                // 更新MD5缓存
                if (vectorStore instanceof InMemoryVectorStore) {
                    InMemoryVectorStore inMemoryStore = (InMemoryVectorStore) vectorStore;
                    inMemoryStore.updateFileMD5(relativePath, currentMD5);
                }

                context.getOutputFormatter().printInfo("✓ " + relativePath + ": " + 
                        (deleted > 0 ? "更新" : "新增") + " " + 
                        (chunks != null ? chunks.size() : 0) + "个片段");
            }

            // 保存索引
            if (vectorIndexConfig != null) {
                Path indexPath = Paths.get(vectorIndexConfig.getIndexPath());
                vectorStore.save().block();
            }

            context.getOutputFormatter().printSuccess(
                    String.format("✅ 增量更新完成: 新增%d, 更新%d, 跳过%d", 
                            added, updated, skipped));

        } catch (Exception e) {
            log.error("增量更新索引失败", e);
            context.getOutputFormatter().printError("更新失败: " + e.getMessage());
        }
    }

    private void handleQuery(CommandContext context, String[] args) {
        if (args.length < 2) {
            context.getOutputFormatter().printError("缺少查询文本");
            context.getOutputFormatter().printInfo("   用法: /index query <查询文本>");
            return;
        }

        if (retrievalPipeline == null) {
            context.getOutputFormatter().printError("检索管线未启用");
            return;
        }

        // 拼接查询文本（从第2个参数开始）
        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        
        context.getOutputFormatter().printInfo("🔍 查询索引: " + query);

        try {
            RetrievalPipeline.RetrievalResult result = 
                    retrievalPipeline.retrieve(query, 5, null).block();

            if (result == null || result.getTotalRetrieved() == 0) {
                context.getOutputFormatter().printWarning("没有找到相关结果");
                return;
            }

            context.getOutputFormatter().printInfo("\n找到 " + result.getTotalRetrieved() + "个相关片段（用时 " + result.getElapsedMs() + "ms）:");
            
            int index = 1;
            for (VectorStore.SearchResult sr : result.getResults()) {
                CodeChunk chunk = sr.getChunk();
                context.getOutputFormatter().printInfo("\n" + index + ". " + chunk.getDescription() + 
                        " (score: " + String.format("%.3f", sr.getScore()) + ")");
                context.getOutputFormatter().printInfo("   预览: " + 
                        chunk.getContent().substring(0, Math.min(100, chunk.getContent().length())) + "...");
                index++;
            }

        } catch (Exception e) {
            log.error("查询索引失败", e);
            context.getOutputFormatter().printError("查询失败: " + e.getMessage());
        }
    }

    private void handleStats(CommandContext context) {
        context.getOutputFormatter().printInfo("📊 获取索引统计...");
        
        vectorStore.getStats()
            .doOnNext(stats -> {
                context.getOutputFormatter().printInfo("\n索引统计信息:");
                context.getOutputFormatter().printInfo("  片段总数: " + stats.getTotalChunks());
                context.getOutputFormatter().printInfo("  文件总数: " + stats.getTotalFiles());
                context.getOutputFormatter().printInfo("  存储类型: " + stats.getStorageType());
                context.getOutputFormatter().printInfo("  索引大小: " + formatBytes(stats.getIndexSizeBytes()));
                if (stats.getLastUpdated() > 0) {
                    context.getOutputFormatter().printInfo("  最后更新: " + 
                        new java.util.Date(stats.getLastUpdated()));
                }
            })
            .doOnError(e -> {
                log.error("获取索引统计失败", e);
                context.getOutputFormatter().printError("获取统计信息失败: " + e.getMessage());
            })
            .block();
    }

    private void handleClear(CommandContext context) {
        if (vectorStore == null) {
            context.getOutputFormatter().printError("索引未启用");
            return;
        }
        
        context.getOutputFormatter().printWarning("⚠️  清空索引将删除所有片段和MD5缓存，此操作不可恢复！");
        context.getOutputFormatter().printInfo("请重新输入命令确认: /index clear --confirm");
    }
    
    private void handleClearConfirmed(CommandContext context) {
        if (vectorStore == null) {
            context.getOutputFormatter().printError("索引未启用");
            return;
        }
        
        context.getOutputFormatter().printInfo("🗑️  正在清空索引...");
        
        try {
            // 获取当前统计
            VectorStore.IndexStats statsBefore = vectorStore.getStats().block();
            
            // 清空索引
            vectorStore.clear().block();
            
            // 清空MD5缓存
            if (vectorStore instanceof InMemoryVectorStore) {
                InMemoryVectorStore inMemoryStore = (InMemoryVectorStore) vectorStore;
                inMemoryStore.updateFileMD5("", ""); // 清空内部Map
            }
            
            // 保存空索引（覆盖旧文件）
            if (vectorIndexConfig != null) {
                Path indexPath = Paths.get(vectorIndexConfig.getIndexPath());
                vectorStore.save().block();
            }
            
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
        context.getOutputFormatter().printInfo("  /index build [path] [--chunk-size=N] [--overlap=N]");
        context.getOutputFormatter().printInfo("      构建索引（path默认为当前工作目录）");
        context.getOutputFormatter().printInfo("  /index update [path]");
        context.getOutputFormatter().printInfo("      增量更新索引");
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

    /**
     * 扫描源文件
     */
    private List<Path> scanSourceFiles(Path basePath) throws Exception {
        List<Path> files = new ArrayList<>();
        
        // 获取支持的文件扩展名
        List<String> extensions = vectorIndexConfig != null 
                ? Arrays.asList(vectorIndexConfig.getFileExtensions().split(","))
                : Arrays.asList(".java", ".kt", ".py", ".js", ".ts");
        
        // 获取排除模式
        List<String> excludePatterns = vectorIndexConfig != null
                ? Arrays.asList(vectorIndexConfig.getExcludePatterns().split(","))
                : Arrays.asList("**/target/**", "**/build/**", "**/node_modules/**");

        if (Files.isDirectory(basePath)) {
            try (Stream<Path> stream = Files.walk(basePath)) {
                files = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String fileName = p.toString();
                            // 检查扩展名
                            boolean hasValidExtension = extensions.stream()
                                    .anyMatch(ext -> fileName.endsWith(ext.trim()));
                            if (!hasValidExtension) {
                                return false;
                            }
                            // 检查排除模式
                            String relativePath = basePath.relativize(p).toString();
                            for (String pattern : excludePatterns) {
                                String regex = pattern.trim()
                                        .replace("**", ".*")
                                        .replace("*", "[^/]*");
                                if (relativePath.matches(regex)) {
                                    return false;
                                }
                            }
                            return true;
                        })
                        .collect(Collectors.toList());
            }
        } else if (Files.isRegularFile(basePath)) {
            files.add(basePath);
        }
        
        return files;
    }

    /**
     * 计算MD5哈希
     */
    private String calculateMD5(String content) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to calculate MD5", e);
            return "";
        }
    }
}
