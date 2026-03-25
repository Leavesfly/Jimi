package io.leavesfly.jimi.knowledge.graph;

import io.leavesfly.jimi.config.info.GraphConfig;
import io.leavesfly.jimi.core.engine.runtime.Runtime;
import io.leavesfly.jimi.knowledge.domain.query.GraphQuery;
import io.leavesfly.jimi.knowledge.domain.result.GraphResult;
import io.leavesfly.jimi.knowledge.graph.builder.GraphBuilder;
import io.leavesfly.jimi.knowledge.graph.model.CodeEntity;
import io.leavesfly.jimi.knowledge.graph.model.CodeRelation;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
     * 初始化图谱服务
     *
     * @param runtime 运行时环境
     * @return 初始化结果
     */
    public Mono<Boolean> initialize(Runtime runtime) {
        return Mono.fromRunnable(() -> {
            setWorkDir(runtime.getWorkDir());
            log.info("GraphService 初始化完成, workDir={}", runtime.getWorkDir());
        }).thenReturn(true);
    }

    /**
     * 查询代码图谱
     *
     * @param query 图谱查询请求
     * @return 查询结果
     */
    public Mono<GraphResult> query(GraphQuery query) {
        if (!isEnabled() || !isInitialized()) {
            return Mono.just(GraphResult.error("Graph 服务未就绪"));
        }

        return Mono.fromCallable(() -> {
            List<GraphResult.GraphEntity> entities = new ArrayList<>();
            List<GraphResult.GraphRelation> relations = new ArrayList<>();
            String mermaidDiagram = null;

            switch (query.getType()) {
                case SEARCH_BY_SYMBOL:
                    entities = searchBySymbol(query.getKeyword(), query.getLimit());
                    break;

                case FIND_DEPENDENCIES:
                    // 使用 ImpactAnalyzer 的 UPSTREAM 分析（我依赖谁）
                    var upstreamResult = impactAnalyzer
                            .analyzeImpact(query.getEntityId(),
                                    ImpactAnalyzer.AnalysisType.UPSTREAM,
                                    query.getMaxDepth()).block();
                    if (upstreamResult != null && upstreamResult.getSuccess()) {
                        entities = convertEntities(upstreamResult.getUpstreamEntities());
                    }
                    break;

                case FIND_DEPENDENTS:
                    // 使用 ImpactAnalyzer 的 DOWNSTREAM 分析（谁依赖我）
                    var downstreamResult = impactAnalyzer
                            .analyzeImpact(query.getEntityId(),
                                    ImpactAnalyzer.AnalysisType.DOWNSTREAM,
                                    query.getMaxDepth()).block();
                    if (downstreamResult != null && downstreamResult.getSuccess()) {
                        entities = convertEntities(downstreamResult.getDownstreamEntities());
                    }
                    break;

                case IMPACT_ANALYSIS:
                    // 双向影响分析
                    var impactResult = impactAnalyzer
                            .analyzeImpact(query.getEntityId(),
                                    ImpactAnalyzer.AnalysisType.BOTH,
                                    query.getMaxDepth()).block();
                    if (impactResult != null && impactResult.getSuccess()) {
                        Set<CodeEntity> allImpacted = new HashSet<>();
                        allImpacted.addAll(impactResult.getDownstreamEntities());
                        allImpacted.addAll(impactResult.getUpstreamEntities());
                        entities = convertEntities(new ArrayList<>(allImpacted));
                    }
                    break;

                case CALL_CHAIN:
                    // 使用 findCallers/findCallees 组合调用链
                    var callees = navigator
                            .findCallees(query.getEntityId(), query.getMaxDepth()).block();
                    if (callees != null) {
                        entities = convertEntities(callees);
                    }
                    break;

                case INHERITANCE_TREE:
                    var inheritance = navigator
                            .getInheritanceHierarchy(query.getEntityId(),
                                    GraphNavigator.Direction.BOTH).block();
                    if (inheritance != null) {
                        List<CodeEntity> allEntities = new ArrayList<>();
                        if (inheritance.getRootEntity() != null) {
                            allEntities.add(inheritance.getRootEntity());
                        }
                        allEntities.addAll(inheritance.getParentClasses());
                        allEntities.addAll(inheritance.getChildClasses());
                        allEntities.addAll(inheritance.getImplementedInterfaces());
                        entities = convertEntities(allEntities);
                    }
                    break;

                case GET_ENTITY_DETAIL:
                    CodeEntity entity = graphStore
                            .getEntity(query.getEntityId()).block();
                    if (entity != null) {
                        entities = List.of(convertEntity(entity));
                    }
                    break;
            }

            // 生成 Mermaid 图（如果请求）
            if (query.isGenerateDiagram() && !entities.isEmpty()) {
                String startId = entities.get(0).getId();
                mermaidDiagram = visualizer
                        .exportCallGraphToMermaid(startId, 2).block();
            }

            return GraphResult.builder()
                    .success(true)
                    .entities(entities)
                    .relations(relations)
                    .mermaidDiagram(mermaidDiagram)
                    .build();
        }).onErrorResume(e -> {
            log.error("查询图谱失败", e);
            return Mono.just(GraphResult.error(e.getMessage()));
        });
    }

    private List<GraphResult.GraphEntity> searchBySymbol(String keyword, int limit) {
        GraphSearchEngine.GraphSearchResult result = searchEngine
                .searchBySymbol(keyword, null, limit).block();

        if (result == null || !result.getSuccess()) {
            return new ArrayList<>();
        }

        return result.getResults().stream()
                .map(scored -> convertEntity(scored.getEntity()))
                .collect(Collectors.toList());
    }

    /**
     * 分析代码变更的影响范围
     *
     * @param entityId 实体ID
     * @param analysisType 分析类型: DOWNSTREAM/UPSTREAM/BOTH
     * @param maxDepth 最大深度
     * @return 影响分析结果
     */
    public Mono<ImpactAnalysisResult> analyzeImpact(String entityId, String analysisType, int maxDepth) {
        if (!isEnabled() || !isInitialized()) {
            return Mono.just(new ImpactAnalysisResultImpl(false, "图谱服务未就绪"));
        }

        return Mono.fromCallable(() -> {
            ImpactAnalyzer.AnalysisType type;
            try {
                type = ImpactAnalyzer.AnalysisType.valueOf(analysisType.toUpperCase());
            } catch (IllegalArgumentException e) {
                type = ImpactAnalyzer.AnalysisType.DOWNSTREAM;
            }

            var result = impactAnalyzer
                    .analyzeImpact(entityId, type, maxDepth).block();

            if (result == null || !result.getSuccess()) {
                return new ImpactAnalysisResultImpl(false,
                        result != null ? result.getErrorMessage() : "分析失败");
            }

            return new ImpactAnalysisResultImpl(result);
        });
    }

    /**
     * 查找调用者
     *
     * @param methodId 方法ID
     * @param maxDepth 最大深度
     * @return 调用者列表
     */
    public Mono<List<EntityInfo>> findCallers(String methodId, int maxDepth) {
        if (!isEnabled() || !isInitialized()) {
            return Mono.just(new ArrayList<>());
        }

        return navigator.findCallers(methodId, maxDepth)
                .map(this::convertToEntityInfoList);
    }

    /**
     * 查找被调用者
     *
     * @param methodId 方法ID
     * @param maxDepth 最大深度
     * @return 被调用者列表
     */
    public Mono<List<EntityInfo>> findCallees(String methodId, int maxDepth) {
        if (!isEnabled() || !isInitialized()) {
            return Mono.just(new ArrayList<>());
        }

        return navigator.findCallees(methodId, maxDepth)
                .map(this::convertToEntityInfoList);
    }

    /**
     * 查找调用链
     *
     * @param sourceMethodId 源方法ID
     * @param targetMethodId 目标方法ID
     * @param maxDepth 最大深度
     * @return 调用链列表
     */
    public Mono<List<CallChainInfo>> findCallChains(String sourceMethodId, String targetMethodId, int maxDepth) {
        if (!isEnabled() || !isInitialized()) {
            return Mono.just(new ArrayList<>());
        }

        return navigator.findCallChains(sourceMethodId, targetMethodId, maxDepth)
                .map(chains -> chains.stream()
                        .map(chain -> (CallChainInfo) new CallChainInfoImpl(chain))
                        .collect(Collectors.toList()));
    }

    /**
     * 生成调用图 Mermaid 可视化
     *
     * @param methodId 方法ID
     * @param maxDepth 最大深度
     * @return Mermaid 图表代码
     */
    public Mono<String> exportCallGraphToMermaid(String methodId, int maxDepth) {
        if (!isEnabled() || !isInitialized()) {
            return Mono.just("");
        }

        return visualizer.exportCallGraphToMermaid(methodId, maxDepth);
    }

    // ==================== 转换方法 ====================

    private List<GraphResult.GraphEntity> convertEntities(List<CodeEntity> entities) {
        if (entities == null) return new ArrayList<>();
        return entities.stream()
                .map(this::convertEntity)
                .collect(Collectors.toList());
    }

    private GraphResult.GraphEntity convertEntity(CodeEntity entity) {
        return GraphResult.GraphEntity.builder()
                .id(entity.getId())
                .name(entity.getName())
                .qualifiedName(entity.getQualifiedName())
                .type(entity.getType().name())
                .filePath(entity.getFilePath())
                .startLine(entity.getStartLine())
                .endLine(entity.getEndLine())
                .metadata(entity.getAttributes())
                .build();
    }

    private List<GraphResult.GraphRelation> convertRelations(List<CodeRelation> relations) {
        if (relations == null) return new ArrayList<>();
        return relations.stream()
                .map(this::convertRelation)
                .collect(Collectors.toList());
    }

    private GraphResult.GraphRelation convertRelation(CodeRelation relation) {
        return GraphResult.GraphRelation.builder()
                .sourceId(relation.getSourceId())
                .targetId(relation.getTargetId())
                .type(relation.getType().name())
                .metadata(relation.getProperties())
                .build();
    }

    private List<EntityInfo> convertToEntityInfoList(List<CodeEntity> entities) {
        if (entities == null) return new ArrayList<>();
        return entities.stream()
                .map(e -> (EntityInfo) new EntityInfoImpl(e))
                .collect(Collectors.toList());
    }

    // ==================== 内部数据结构 ====================

    /**
     * 影响分析结果
     */
    public interface ImpactAnalysisResult {
        boolean isSuccess();
        String getErrorMessage();
        String getTargetEntityId();
        String getTargetEntityName();
        String getAnalysisType();
        int getMaxDepth();
        List<EntityInfo> getDownstreamEntities();
        List<EntityInfo> getUpstreamEntities();
        Map<String, Integer> getDownstreamByType();
        Map<String, Integer> getUpstreamByType();
        int getTotalImpactedEntities();
    }

    /**
     * 实体信息
     */
    public interface EntityInfo {
        String getId();
        String getName();
        String getType();
        String getFilePath();
        int getStartLine();
    }

    /**
     * 调用链信息
     */
    public interface CallChainInfo {
        String getPathString();
        int getDepth();
        List<EntityInfo> getNodes();
    }

    /**
     * 实体信息实现
     */
    private static class EntityInfoImpl implements EntityInfo {
        private final String id;
        private final String name;
        private final String type;
        private final String filePath;
        private final int startLine;

        EntityInfoImpl(CodeEntity entity) {
            this.id = entity.getId();
            this.name = entity.getName();
            this.type = entity.getType().name();
            this.filePath = entity.getFilePath();
            this.startLine = entity.getStartLine();
        }

        @Override public String getId() { return id; }
        @Override public String getName() { return name; }
        @Override public String getType() { return type; }
        @Override public String getFilePath() { return filePath; }
        @Override public int getStartLine() { return startLine; }
    }

    /**
     * 调用链信息实现
     */
    private static class CallChainInfoImpl implements CallChainInfo {
        private final String pathString;
        private final int depth;
        private final List<EntityInfo> nodes;

        CallChainInfoImpl(GraphNavigator.CallChain chain) {
            this.pathString = chain.getPathString();
            this.depth = chain.getDepth();
            this.nodes = chain.getPath().stream()
                    .map(e -> (EntityInfo) new EntityInfoImpl(e))
                    .collect(Collectors.toList());
        }

        @Override public String getPathString() { return pathString; }
        @Override public int getDepth() { return depth; }
        @Override public List<EntityInfo> getNodes() { return nodes; }
    }

    /**
     * 影响分析结果实现
     */
    private static class ImpactAnalysisResultImpl implements ImpactAnalysisResult {
        private final boolean success;
        private final String errorMessage;
        private final String targetEntityId;
        private final String targetEntityName;
        private final String analysisType;
        private final int maxDepth;
        private final List<EntityInfo> downstreamEntities;
        private final List<EntityInfo> upstreamEntities;
        private final Map<String, Integer> downstreamByType;
        private final Map<String, Integer> upstreamByType;
        private final int totalImpactedEntities;

        ImpactAnalysisResultImpl(boolean success, String errorMessage) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.targetEntityId = null;
            this.targetEntityName = null;
            this.analysisType = null;
            this.maxDepth = 0;
            this.downstreamEntities = new ArrayList<>();
            this.upstreamEntities = new ArrayList<>();
            this.downstreamByType = new HashMap<>();
            this.upstreamByType = new HashMap<>();
            this.totalImpactedEntities = 0;
        }

        ImpactAnalysisResultImpl(ImpactAnalyzer.ImpactAnalysisResult result) {
            this.success = result.getSuccess();
            this.errorMessage = result.getErrorMessage();
            this.targetEntityId = result.getTargetEntityId();
            this.targetEntityName = result.getTargetEntity() != null ?
                    result.getTargetEntity().getName() : null;
            this.analysisType = result.getAnalysisType().name();
            this.maxDepth = result.getMaxDepth();
            this.downstreamEntities = result.getDownstreamEntities().stream()
                    .map(e -> (EntityInfo) new EntityInfoImpl(e))
                    .collect(Collectors.toList());
            this.upstreamEntities = result.getUpstreamEntities().stream()
                    .map(e -> (EntityInfo) new EntityInfoImpl(e))
                    .collect(Collectors.toList());
            this.downstreamByType = result.getDownstreamByType().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
            this.upstreamByType = result.getUpstreamByType().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
            this.totalImpactedEntities = result.getTotalImpactedEntities();
        }

        @Override public boolean isSuccess() { return success; }
        @Override public String getErrorMessage() { return errorMessage; }
        @Override public String getTargetEntityId() { return targetEntityId; }
        @Override public String getTargetEntityName() { return targetEntityName; }
        @Override public String getAnalysisType() { return analysisType; }
        @Override public int getMaxDepth() { return maxDepth; }
        @Override public List<EntityInfo> getDownstreamEntities() { return downstreamEntities; }
        @Override public List<EntityInfo> getUpstreamEntities() { return upstreamEntities; }
        @Override public Map<String, Integer> getDownstreamByType() { return downstreamByType; }
        @Override public Map<String, Integer> getUpstreamByType() { return upstreamByType; }
        @Override public int getTotalImpactedEntities() { return totalImpactedEntities; }
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
