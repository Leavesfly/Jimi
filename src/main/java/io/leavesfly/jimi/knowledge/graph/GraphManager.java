package io.leavesfly.jimi.knowledge.graph;

import io.leavesfly.jimi.config.info.GraphConfig;
import io.leavesfly.jimi.knowledge.graph.builder.GraphBuilder;
import io.leavesfly.jimi.knowledge.graph.navigator.GraphNavigator;
import io.leavesfly.jimi.knowledge.graph.navigator.ImpactAnalyzer;
import io.leavesfly.jimi.knowledge.graph.parser.JavaASTParser;
import io.leavesfly.jimi.knowledge.graph.store.CodeGraphStore;
import io.leavesfly.jimi.knowledge.graph.store.InMemoryCodeGraphStore;
import io.leavesfly.jimi.knowledge.graph.visualization.GraphVisualizer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 代码图管理器 - 统一管理代码图的生命周期
 *
 * <p>职责：
 * <ul>
 *   <li>初始化和销毁代码图</li>
 *   <li>提供代码图构建接口</li>
 *   <li>提供统一的图查询、导航、分析能力</li>
 *   <li>管理图的状态和缓存</li>
 * </ul>
 */
@Slf4j
@Component
public class GraphManager {

    @Getter
    private final GraphConfig config;

    @Getter
    private final CodeGraphStore graphStore;

    @Getter
    private final GraphBuilder graphBuilder;

    @Getter
    private final GraphNavigator navigator;

    @Getter
    private final ImpactAnalyzer impactAnalyzer;

    @Getter
    private final GraphSearchEngine searchEngine;

    @Getter
    private final GraphVisualizer visualizer;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicReference<Path> currentProjectRoot = new AtomicReference<>();

    /**
     * 当前工作目录（从 Session 获取）
     * 用于持久化操作的路径计算
     */
    private volatile Path workDir;

    @Autowired
    public GraphManager(GraphConfig config) {
        this.config = config;

        // 初始化核心组件
        this.graphStore = new InMemoryCodeGraphStore();
        JavaASTParser javaParser = new JavaASTParser();
        this.graphBuilder = new GraphBuilder(javaParser, graphStore);
        this.navigator = new GraphNavigator(graphStore);
        this.impactAnalyzer = new ImpactAnalyzer(graphStore);
        this.searchEngine = new GraphSearchEngine(graphStore, navigator);
        this.visualizer = new GraphVisualizer(graphStore);

        log.info("GraphManager initialized with config: enabled={}, autoBuild={}, buildOnStartup={}, autoLoad={}",
                config.getEnabled(), config.getAutoBuild(), config.getBuildOnStartup(), config.getAutoLoad());
    }

    /**
     * 设置工作目录
     * 应在 Session 创建后调用，用于持久化路径计算
     *
     * @param workDir 工作目录
     */
    public void setWorkDir(Path workDir) {
        if (this.workDir != null && this.workDir.equals(workDir)) {
            return; // 已经设置且相同，无需重复设置
        }

        this.workDir = workDir;
        log.debug("Work directory set to: {}", workDir);

        // 设置工作目录后，如果启用了自动加载，尝试加载已保存的图
        if (config.getEnabled() && config.getAutoLoad() && !initialized.get()) {
            Path storagePath = resolveStoragePath();
            if (Files.exists(storagePath)) {
                graphStore.load(storagePath)
                        .doOnSuccess(success -> {
                            if (success) {
                                initialized.set(true);
                                log.info("Auto-loaded code graph from: {}", storagePath);
                            } else {
                                log.debug("No existing graph found at: {}", storagePath);
                            }
                        })
                        .doOnError(e -> log.warn("Failed to auto-load graph: {}", e.getMessage()))
                        .onErrorResume(e -> Mono.just(false))
                        .subscribe();
            }
        }
    }

    /**
     * 确保工作目录已初始化
     * 如果 workDir 为 null，使用 user.dir 作为默认值并触发自动加载
     * 此方法应在检索前调用，确保有工作目录可用
     */
    public void ensureWorkDirInitialized() {
        if (workDir == null) {
            Path defaultWorkDir = Paths.get(System.getProperty("user.dir"));
            log.debug("Work directory not set, using default: {}", defaultWorkDir);
            setWorkDir(defaultWorkDir);
        }
    }

    /**
     * 解析存储路径
     * 优先使用 workDir（从 Session 获取），回退到 System.getProperty("user.dir")
     *
     * @return 存储路径
     */
    private Path resolveStoragePath() {
        Path baseDir = (workDir != null) ? workDir : Paths.get(System.getProperty("user.dir"));
        return baseDir.resolve(config.getStoragePath());
    }

    /**
     * 是否启用代码图功能
     */
    public boolean isEnabled() {
        return config.getEnabled();
    }

    /**
     * 是否已初始化
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * 构建代码图
     *
     * @param projectRoot 项目根目录
     * @return 构建结果 (实体数, 关系数)
     */
    public Mono<BuildResult> buildGraph(Path projectRoot) {
        if (!config.getEnabled()) {
            log.warn("Graph feature is disabled, skipping build");
            return Mono.just(BuildResult.disabled());
        }

        log.info("Building code graph for project: {}", projectRoot);
        long startTime = System.currentTimeMillis();

        return graphBuilder.buildGraph(projectRoot)
                .map(buildStats -> {
                    long duration = System.currentTimeMillis() - startTime;
                    currentProjectRoot.set(projectRoot);
                    initialized.set(true);

                    int entityCount = buildStats.getTotalEntities();
                    int relationCount = buildStats.getTotalRelations();

                    log.info("Code graph built successfully in {}ms: {} entities, {} relations",
                            duration, entityCount, relationCount);

                    return new BuildResult(entityCount, relationCount);
                })
                .flatMap(result -> {
                    // 自动保存
                    if (config.getAutoSave() && result.isSuccess()) {
                        Path storagePath = resolveStoragePath();
                        return graphStore.save()
                                .doOnSuccess(saved -> {
                                    if (saved) {
                                        log.info("Auto-saved code graph to: {}", storagePath);
                                    } else {
                                        log.warn("Failed to auto-save code graph");
                                    }
                                })
                                .thenReturn(result);
                    }
                    return Mono.just(result);
                })
                .doOnError(error -> {
                    log.error("Failed to build code graph", error);
                });
    }

    /**
     * 重新构建代码图
     */
    public Mono<BuildResult> rebuildGraph() {
        Path projectRoot = currentProjectRoot.get();
        if (projectRoot == null) {
            return Mono.error(new IllegalStateException("No project root set, cannot rebuild"));
        }

        log.info("Rebuilding code graph...");
        clearGraph();
        return buildGraph(projectRoot);
    }

    /**
     * 清空代码图
     */
    public void clearGraph() {
        log.info("Clearing code graph...");
        graphStore.clear().block();
        initialized.set(false);
        log.info("Code graph cleared");
    }

    /**
     * 获取图统计信息
     */
    public Mono<GraphStats> getGraphStats() {
        return graphStore.getStats()
                .map(stats -> GraphStats.builder()
                        .entityCount(stats.getTotalEntities())
                        .relationCount(stats.getTotalRelations())
                        .initialized(initialized.get())
                        .projectRoot(currentProjectRoot.get())
                        .build());
    }

    /**
     * 导出 Mermaid 可视化
     */
    public Mono<String> exportMermaid(String startEntityId, int maxDepth) {
        return visualizer.exportCallGraphToMermaid(startEntityId, maxDepth);
    }

    /**
     * 手动保存代码图
     *
     * @return 是否成功
     */
    public Mono<Boolean> saveGraph() {
        if (!initialized.get()) {
            log.warn("Graph not initialized, nothing to save");
            return Mono.just(false);
        }

        Path storagePath = resolveStoragePath();
        log.info("Saving code graph to: {}", storagePath);

        return graphStore.save()
                .doOnSuccess(success -> {
                    if (success) {
                        log.info("Code graph saved successfully");
                    } else {
                        log.error("Failed to save code graph");
                    }
                });
    }

    /**
     * 手动加载代码图
     *
     * @return 是否成功
     */
    public Mono<Boolean> loadGraph() {
        Path storagePath = resolveStoragePath();
        log.info("Loading code graph from: {}", storagePath);

        return graphStore.load(storagePath)
                .doOnSuccess(success -> {
                    if (success) {
                        initialized.set(true);
                        log.info("Code graph loaded successfully");
                    } else {
                        log.warn("Failed to load code graph");
                    }
                });
    }

    /**
     * 构建结果
     */
    @Getter
    public static class BuildResult {
        private final int entityCount;
        private final int relationCount;
        private final boolean success;

        public BuildResult(int entityCount, int relationCount) {
            this.entityCount = entityCount;
            this.relationCount = relationCount;
            this.success = true;
        }

        private BuildResult() {
            this.entityCount = 0;
            this.relationCount = 0;
            this.success = false;
        }

        public static BuildResult disabled() {
            return new BuildResult();
        }
    }

    /**
     * 图统计信息
     */
    @Getter
    @lombok.Builder
    public static class GraphStats {
        private final int entityCount;
        private final int relationCount;
        private final boolean initialized;
        private final Path projectRoot;

        @Override
        public String toString() {
            return String.format(
                    "GraphStats[entities=%d, relations=%d, initialized=%s, project=%s]",
                    entityCount, relationCount, initialized, projectRoot
            );
        }
    }
}
