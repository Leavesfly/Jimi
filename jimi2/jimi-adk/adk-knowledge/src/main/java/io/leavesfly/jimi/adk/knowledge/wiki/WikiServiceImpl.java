package io.leavesfly.jimi.adk.knowledge.wiki;

import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.knowledge.query.WikiQuery;
import io.leavesfly.jimi.adk.api.knowledge.result.WikiResult;
import io.leavesfly.jimi.adk.api.knowledge.spi.WikiService;
import io.leavesfly.jimi.adk.api.llm.LLM;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Wiki服务实现（适配 WikiGenerator）
 * 
 * <p>复用现有的 WikiGenerator 实现，提供 SPI 接口适配。
 */
@Slf4j
public class WikiServiceImpl implements WikiService {
    
    private final WikiGenerator wikiGenerator;
    private final ChangeDetector changeDetector;
    private final WikiValidator wikiValidator;
    private final WikiIndexManager wikiIndexManager;
    
    private volatile Path workDir;
    private volatile Runtime runtime;
    private volatile boolean enabled = true;
    
    public WikiServiceImpl(WikiGenerator wikiGenerator,
                           ChangeDetector changeDetector,
                           WikiValidator wikiValidator,
                           WikiIndexManager wikiIndexManager) {
        this.wikiGenerator = wikiGenerator;
        this.changeDetector = changeDetector;
        this.wikiValidator = wikiValidator;
        this.wikiIndexManager = wikiIndexManager;
    }
    
    @Override
    public Mono<Boolean> initialize(Runtime runtime) {
        return Mono.fromRunnable(() -> {
            this.runtime = runtime;
            this.workDir = runtime.getWorkDir();
            log.info("WikiService 初始化完成, workDir={}", workDir);
        }).thenReturn(true);
    }
    
    @Override
    public Mono<WikiResult> generate(WikiQuery query) {
        if (!isEnabled()) {
            return Mono.just(WikiResult.error("Wiki 功能未启用"));
        }
        
        Path projectRoot = query.getProjectRoot() != null ? query.getProjectRoot() : workDir;
        if (projectRoot == null) {
            return Mono.just(WikiResult.error("项目路径未指定"));
        }
        
        Path wikiPath = projectRoot.resolve(".jimi").resolve("wiki");
        
        // 从 Runtime 获取 LLM
        LLM llm = runtime != null ? runtime.getLlm() : null;
        if (llm == null) {
            // 没有 LLM 时生成占位文档
            log.info("未配置 LLM，生成占位 Wiki 文档");
            return generatePlaceholderWiki(wikiPath, projectRoot);
        }
        
        return Mono.fromFuture(wikiGenerator.generateWiki(wikiPath, projectRoot.toString(), llm))
                .map(result -> {
                    if (result.isSuccess()) {
                        return WikiResult.builder()
                                .success(true)
                                .title("Wiki 文档")
                                .documentPath(wikiPath)
                                .build();
                    } else {
                        return WikiResult.error(result.getErrorMessage());
                    }
                })
                .onErrorResume(e -> {
                    log.error("Wiki 生成失败", e);
                    return Mono.just(WikiResult.error(e.getMessage()));
                });
    }
    
    /**
     * 生成占位 Wiki 文档（无 Engine 时使用）
     */
    private Mono<WikiResult> generatePlaceholderWiki(Path wikiPath, Path projectRoot) {
        return Mono.fromCallable(() -> {
            try {
                Files.createDirectories(wikiPath);
                
                // 生成 README
                Path readmePath = wikiPath.resolve("README.md");
                StringBuilder readme = new StringBuilder();
                readme.append("# 项目 Wiki\n\n");
                readme.append("> 生成时间: ").append(java.time.LocalDateTime.now()).append("\n\n");
                readme.append("此文档待完善。\n\n");
                readme.append("提示：请配置 LLM 以启用智能生成功能。\n");
                Files.writeString(readmePath, readme.toString());
                
                // 生成架构文档目录
                Path archDir = wikiPath.resolve("architecture");
                Files.createDirectories(archDir);
                
                Path overviewPath = archDir.resolve("overview.md");
                Files.writeString(overviewPath, "# 系统架构概览\n\n此文档待完善。\n");
                
                return WikiResult.builder()
                        .success(true)
                        .title("Wiki 文档（占位）")
                        .documentPath(wikiPath)
                        .build();
            } catch (Exception e) {
                log.error("生成占位 Wiki 失败", e);
                return WikiResult.error(e.getMessage());
            }
        });
    }
    
    @Override
    public Mono<WikiResult> detectAndUpdate(Path projectRoot) {
        if (!isEnabled()) {
            return Mono.just(WikiResult.error("Wiki 功能未启用"));
        }
        
        if (projectRoot == null) {
            projectRoot = workDir;
        }
        
        if (projectRoot == null) {
            return Mono.just(WikiResult.error("项目路径未指定"));
        }
        
        Path wikiPath = projectRoot.resolve(".jimi").resolve("wiki");
        
        final Path finalProjectRoot = projectRoot;
        
        return Mono.fromCallable(() -> {
            // 检测变更
            List<FileChange> changes = changeDetector.detectChanges(finalProjectRoot);
            
            // 转换为结果格式
            List<WikiResult.FileChange> resultChanges = changes.stream()
                    .map(change -> WikiResult.FileChange.builder()
                            .filePath(change.getAbsolutePath())
                            .changeType(change.getChangeType().name())
                            .needsUpdate(change.getImportance() != null && 
                                    change.getImportance() != FileChange.ChangeImportance.TRIVIAL)
                            .build())
                    .collect(Collectors.toList());
            
            // 如果有需要更新的文件，触发更新
            boolean hasUpdates = changes.stream()
                    .anyMatch(c -> c.getImportance() != null && 
                            c.getImportance() != FileChange.ChangeImportance.TRIVIAL);
            if (hasUpdates) {
                log.info("检测到 {} 个需要更新的文件", 
                        changes.stream()
                                .filter(c -> c.getImportance() != null && 
                                        c.getImportance() != FileChange.ChangeImportance.TRIVIAL)
                                .count());
                // 可以在这里触发增量更新
            }
            
            return WikiResult.builder()
                    .success(true)
                    .changes(resultChanges)
                    .build();
        }).onErrorResume(e -> {
            log.error("变更检测失败", e);
            return Mono.just(WikiResult.error(e.getMessage()));
        });
    }
    
    @Override
    public Mono<WikiResult> search(WikiQuery query) {
        if (!isEnabled()) {
            return Mono.just(WikiResult.error("Wiki 功能未启用"));
        }
        
        Path wikiPath = workDir != null ? 
                workDir.resolve(".jimi").resolve("wiki") : null;
        
        if (wikiPath == null || !Files.exists(wikiPath)) {
            return Mono.just(WikiResult.builder()
                    .success(true)
                    .documents(new ArrayList<>())
                    .build());
        }
        
        String keyword = query.getKeyword();
        int limit = query.getLimit();
        
        return Mono.fromCallable(() -> {
            List<WikiResult.WikiDocument> documents = new ArrayList<>();
            
            // 简单的文件内容搜索
            try (Stream<Path> paths = Files.walk(wikiPath)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".md"))
                        .limit(limit * 2) // 获取更多文件用于过滤
                        .forEach(path -> {
                            try {
                                String content = Files.readString(path);
                                if (keyword == null || content.toLowerCase().contains(keyword.toLowerCase())) {
                                    // 提取标题（第一行 # 开头的内容）
                                    String title = extractTitle(content);
                                    String summary = extractSummary(content);
                                    
                                    documents.add(WikiResult.WikiDocument.builder()
                                            .title(title)
                                            .path(path)
                                            .summary(summary)
                                            .lastUpdated(Files.getLastModifiedTime(path).toMillis())
                                            .build());
                                }
                            } catch (Exception e) {
                                log.warn("读取文件失败: {}", path, e);
                            }
                        });
            }
            
            // 限制返回数量
            List<WikiResult.WikiDocument> limitedDocs = documents.stream()
                    .limit(limit)
                    .collect(Collectors.toList());
            
            return WikiResult.builder()
                    .success(true)
                    .documents(limitedDocs)
                    .build();
        }).onErrorResume(e -> {
            log.error("Wiki 搜索失败", e);
            return Mono.just(WikiResult.error(e.getMessage()));
        });
    }
    
    @Override
    public Mono<WikiResult> validate(WikiQuery query) {
        if (!isEnabled() || wikiValidator == null) {
            return Mono.just(WikiResult.error("Wiki 验证功能未启用"));
        }
        
        Path wikiPath = workDir != null ? 
                workDir.resolve(".jimi").resolve("wiki") : null;
        
        if (wikiPath == null || !Files.exists(wikiPath)) {
            return Mono.just(WikiResult.builder()
                    .success(true)
                    .validation(WikiResult.ValidationResult.builder()
                            .valid(false)
                            .issues(List.of("Wiki 目录不存在"))
                            .build())
                    .build());
        }
        
        return Mono.fromCallable(() -> {
            WikiValidator.ValidationReport report = wikiValidator.validate(wikiPath);
            
            // 转换问题列表
            List<String> issues = new ArrayList<>();
            report.getErrors().forEach(e -> issues.add("[ERROR] " + e.getMessage()));
            report.getWarnings().forEach(w -> issues.add("[WARN] " + w.getMessage()));
            
            List<String> suggestions = new ArrayList<>();
            report.getInfos().forEach(i -> suggestions.add(i.getMessage()));
            
            return WikiResult.builder()
                    .success(true)
                    .validation(WikiResult.ValidationResult.builder()
                            .valid(report.isClean())
                            .issues(issues)
                            .suggestions(suggestions)
                            .build())
                    .build();
        }).onErrorResume(e -> {
            log.error("Wiki 验证失败", e);
            return Mono.just(WikiResult.error(e.getMessage()));
        });
    }
    
    @Override
    public boolean isEnabled() {
        return enabled && wikiGenerator != null;
    }
    
    // ==================== 辅助方法 ====================
    
    private String extractTitle(String content) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return "Untitled";
    }
    
    private String extractSummary(String content) {
        String[] lines = content.split("\n");
        StringBuilder summary = new StringBuilder();
        boolean inContent = false;
        
        for (String line : lines) {
            if (line.startsWith("# ")) {
                inContent = true;
                continue;
            }
            if (inContent && !line.trim().isEmpty() && !line.startsWith("#")) {
                summary.append(line.trim()).append(" ");
                if (summary.length() > 200) {
                    break;
                }
            }
        }
        
        String result = summary.toString().trim();
        if (result.length() > 200) {
            result = result.substring(0, 197) + "...";
        }
        return result;
    }
}
