package io.leavesfly.jimi.knowledge.graph.builder;

import io.leavesfly.jimi.config.info.GraphConfig;
import io.leavesfly.jimi.knowledge.graph.parser.LanguageParser;
import io.leavesfly.jimi.knowledge.graph.parser.LanguageParserRegistry;
import io.leavesfly.jimi.knowledge.graph.parser.ParseResult;
import io.leavesfly.jimi.knowledge.graph.store.CodeGraphStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 代码图构建器
 * <p>
 * 负责扫描项目代码,解析并构建代码图。
 * 支持多语言，通过 LanguageParserRegistry 自动选择合适的解析器。
 */
@Slf4j
@Component
public class GraphBuilder {
    
    private final LanguageParserRegistry parserRegistry;
    private final CodeGraphStore graphStore;
    private final GraphConfig config;
    
    // 缓存的 PathMatcher，避免重复创建
    private List<PathMatcher> includeMatchers;
    private List<PathMatcher> excludeMatchers;
    
    public GraphBuilder(LanguageParserRegistry parserRegistry, CodeGraphStore graphStore, GraphConfig config) {
        this.parserRegistry = parserRegistry;
        this.graphStore = graphStore;
        this.config = config;
        initializeMatchers();
    }
    
    /**
     * 初始化 glob 模式匹配器
     */
    private void initializeMatchers() {
        this.includeMatchers = createMatchers(config.getIncludePatterns());
        this.excludeMatchers = createMatchers(config.getExcludePatterns());
        
        log.debug("Initialized graph builder with {} include patterns, {} exclude patterns",
            includeMatchers.size(), excludeMatchers.size());
    }
    
    /**
     * 创建 PathMatcher 列表
     */
    private List<PathMatcher> createMatchers(Set<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return new ArrayList<>();
        }
        return patterns.stream()
            .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
            .collect(Collectors.toList());
    }
    
    /**
     * 构建整个项目的代码图
     *
     * @param projectRoot 项目根目录
     * @return 构建结果统计
     */
    public Mono<BuildStats> buildGraph(Path projectRoot) {
        log.info("Building code graph for project: {}", projectRoot);
        log.info("Supported languages: {}", parserRegistry.getSupportedLanguages());
        
        return Mono.fromCallable(() -> scanSourceFiles(projectRoot))
            .flatMap(sourceFiles -> {
                log.info("Found {} source files to parse", sourceFiles.size());
                
                return Flux.fromIterable(sourceFiles)
                    .flatMap(file -> parseAndStore(file, projectRoot))
                    .reduce(new BuildStats(), this::mergeStats);
            })
            .doOnSuccess(stats -> {
                log.info("Graph build completed: {}", stats);
            })
            .doOnError(e -> {
                log.error("Failed to build graph", e);
            });
    }
    
    /**
     * 增量更新:解析单个文件并更新图
     *
     * @param filePath 文件路径
     * @param projectRoot 项目根目录
     * @return 更新结果
     */
    public Mono<ParseResult> updateFile(Path filePath, Path projectRoot) {
        log.info("Updating graph for file: {}", filePath);
        
        Optional<LanguageParser> parserOpt = parserRegistry.getParserForFile(filePath);
        if (parserOpt.isEmpty()) {
            log.warn("No parser available for file: {}", filePath);
            return Mono.just(ParseResult.failure(filePath.toString(), "No parser available for this file type"));
        }
        
        LanguageParser parser = parserOpt.get();
        return Mono.fromCallable(() -> parser.parseFile(filePath, projectRoot))
            .flatMap(result -> {
                if (!result.getSuccess()) {
                    return Mono.just(result);
                }
                
                // 先删除该文件的旧数据
                String relativeFilePath = projectRoot.relativize(filePath).toString();
                return graphStore.deleteEntitiesByFile(relativeFilePath)
                    .then(graphStore.addEntities(result.getEntities()))
                    .then(graphStore.addRelations(result.getRelations()))
                    .thenReturn(result);
            })
            .doOnSuccess(result -> {
                log.info("File updated: {} - {}", filePath.getFileName(), result.getStats());
            });
    }
    
    /**
     * 删除文件的代码图数据
     *
     * @param filePath 文件路径
     * @param projectRoot 项目根目录
     * @return 删除的实体数量
     */
    public Mono<Integer> removeFile(Path filePath, Path projectRoot) {
        String relativeFilePath = projectRoot.relativize(filePath).toString();
        log.info("Removing graph data for file: {}", relativeFilePath);
        
        return graphStore.deleteEntitiesByFile(relativeFilePath)
            .doOnSuccess(count -> {
                log.info("Removed {} entities from file: {}", count, relativeFilePath);
            });
    }
    
    /**
     * 清空代码图
     */
    public Mono<Void> clearGraph() {
        log.info("Clearing code graph");
        return graphStore.clear();
    }
    
    /**
     * 获取图统计信息
     */
    public Mono<CodeGraphStore.GraphStats> getGraphStats() {
        return graphStore.getStats();
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 扫描所有可解析的源文件
     */
    private List<Path> scanSourceFiles(Path projectRoot) throws IOException {
        List<Path> sourceFiles = new ArrayList<>();
        
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(parserRegistry::canParse)  // 使用 registry 判断是否可解析
                 .filter(this::matchesIncludePatterns)
                 .filter(path -> !matchesExcludePatterns(path))
                 .forEach(sourceFiles::add);
        }
        
        return sourceFiles;
    }
    
    /**
     * 检查文件是否匹配包含模式
     */
    private boolean matchesIncludePatterns(Path path) {
        if (includeMatchers.isEmpty()) {
            // 无包含模式时，接受所有可解析的文件（已由 canParse 过滤）
            return true;
        }
        return includeMatchers.stream()
            .anyMatch(matcher -> matcher.matches(path) || matcher.matches(path.getFileName()));
    }
    
    /**
     * 检查文件是否匹配排除模式
     */
    private boolean matchesExcludePatterns(Path path) {
        if (excludeMatchers.isEmpty()) {
            return false;
        }
        String pathStr = path.toString();
        
        // 同时检查完整路径和路径字符串
        return excludeMatchers.stream().anyMatch(matcher -> {
            // 尝试匹配完整路径
            if (matcher.matches(path)) {
                return true;
            }
            // 尝试匹配相对路径字符串（用于 **/pattern/** 形式）
            // 检查路径字符串是否包含模式对应的目录
            return pathStr.contains("/target/") ||
                   pathStr.contains("/build/") ||
                   pathStr.contains("/.git/") ||
                   pathStr.contains("/node_modules/") ||
                   pathStr.contains("/test/") ||
                   pathStr.contains("/tests/");
        });
    }
    
    /**
     * 解析文件并存储到图中
     */
    private Mono<BuildStats> parseAndStore(Path filePath, Path projectRoot) {
        Optional<LanguageParser> parserOpt = parserRegistry.getParserForFile(filePath);
        if (parserOpt.isEmpty()) {
            // 不支持的文件类型，跳过
            return Mono.just(new BuildStats());
        }
        
        LanguageParser parser = parserOpt.get();
        return Mono.fromCallable(() -> parser.parseFile(filePath, projectRoot))
            .flatMap(result -> {
                BuildStats stats = new BuildStats();
                stats.totalFiles++;
                
                if (!result.getSuccess()) {
                    stats.failedFiles++;
                    log.warn("Failed to parse file: {} - {}", filePath, result.getErrorMessage());
                    return Mono.just(stats);
                }
                
                stats.successFiles++;
                stats.totalEntities += result.getEntities().size();
                stats.totalRelations += result.getRelations().size();
                
                return graphStore.addEntities(result.getEntities())
                    .then(graphStore.addRelations(result.getRelations()))
                    .thenReturn(stats);
            })
            .onErrorResume(e -> {
                log.error("Error processing file: {}", filePath, e);
                BuildStats stats = new BuildStats();
                stats.totalFiles++;
                stats.failedFiles++;
                return Mono.just(stats);
            });
    }
    
    /**
     * 合并统计信息
     */
    private BuildStats mergeStats(BuildStats a, BuildStats b) {
        a.totalFiles += b.totalFiles;
        a.successFiles += b.successFiles;
        a.failedFiles += b.failedFiles;
        a.totalEntities += b.totalEntities;
        a.totalRelations += b.totalRelations;
        return a;
    }
    
    /**
     * 构建统计信息
     */
    @lombok.Data
    public static class BuildStats {
        private int totalFiles = 0;
        private int successFiles = 0;
        private int failedFiles = 0;
        private int totalEntities = 0;
        private int totalRelations = 0;
        
        @Override
        public String toString() {
            return String.format(
                "Files: %d (success: %d, failed: %d), Entities: %d, Relations: %d",
                totalFiles, successFiles, failedFiles, totalEntities, totalRelations
            );
        }
    }
}
