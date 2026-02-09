package io.leavesfly.jimi.adk.knowledge.graph.builder;

import io.leavesfly.jimi.adk.knowledge.graph.parser.JavaASTParser;
import io.leavesfly.jimi.adk.knowledge.graph.parser.ParseResult;
import io.leavesfly.jimi.adk.knowledge.graph.store.CodeGraphStore;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 代码图构建器
 * <p>
 * 负责扫描项目代码,解析并构建代码图
 */
@Slf4j
public class GraphBuilder {
    
    private final JavaASTParser javaParser;
    private final CodeGraphStore graphStore;
    
    public GraphBuilder(JavaASTParser javaParser, CodeGraphStore graphStore) {
        this.javaParser = javaParser;
        this.graphStore = graphStore;
    }
    
    /**
     * 构建整个项目的代码图
     *
     * @param projectRoot 项目根目录
     * @return 构建结果统计
     */
    public Mono<BuildStats> buildGraph(Path projectRoot) {
        log.info("Building code graph for project: {}", projectRoot);
        
        return Mono.fromCallable(() -> scanJavaFiles(projectRoot))
            .flatMap(javaFiles -> {
                log.info("Found {} Java files", javaFiles.size());
                
                return Flux.fromIterable(javaFiles)
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
        
        return Mono.fromCallable(() -> javaParser.parseFile(filePath, projectRoot))
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
     * 扫描 Java 文件
     */
    private List<Path> scanJavaFiles(Path projectRoot) throws IOException {
        List<Path> javaFiles = new ArrayList<>();
        
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(path -> path.toString().endsWith(".java"))
                 .filter(path -> !isExcluded(path))
                 .forEach(javaFiles::add);
        }
        
        return javaFiles;
    }
    
    /**
     * 判断文件是否应该被排除
     */
    private boolean isExcluded(Path path) {
        String pathStr = path.toString();
        
        // 排除 target、build、.git 等目录
        return pathStr.contains("/target/") ||
               pathStr.contains("/build/") ||
               pathStr.contains("/.git/") ||
               pathStr.contains("/node_modules/") ||
               pathStr.contains("/test/") ||
               pathStr.contains("/.") && !pathStr.endsWith(".java");
    }
    
    /**
     * 解析文件并存储到图中
     */
    private Mono<BuildStats> parseAndStore(Path filePath, Path projectRoot) {
        return Mono.fromCallable(() -> javaParser.parseFile(filePath, projectRoot))
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
