package io.leavesfly.jimi.adk.knowledge.graph;

import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.knowledge.api.query.GraphQuery;
import io.leavesfly.jimi.adk.knowledge.api.result.GraphResult;
import io.leavesfly.jimi.adk.knowledge.api.spi.GraphService;
import io.leavesfly.jimi.adk.knowledge.graph.model.CodeEntity;
import io.leavesfly.jimi.adk.knowledge.graph.model.CodeRelation;
import io.leavesfly.jimi.adk.knowledge.graph.navigator.GraphNavigator;
import io.leavesfly.jimi.adk.knowledge.graph.navigator.ImpactAnalyzer;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 代码图谱服务实现（适配 GraphManager）
 * 
 * <p>复用现有的 GraphManager 实现，提供 SPI 接口适配。
 */
@Slf4j
public class GraphServiceImpl implements GraphService {
    
    private final GraphManager graphManager;
    private final GraphConfig config;
    
    public GraphServiceImpl(GraphManager graphManager, GraphConfig config) {
        this.graphManager = graphManager;
        this.config = config;
    }
    
    @Override
    public Mono<Boolean> initialize(Runtime runtime) {
        return Mono.fromRunnable(() -> {
            graphManager.setWorkDir(runtime.getConfig().getWorkDir());
            log.info("GraphService 初始化完成, workDir={}", runtime.getConfig().getWorkDir());
        }).thenReturn(true);
    }
    
    @Override
    public Mono<GraphResult> build(Path projectRoot) {
        if (!isEnabled()) {
            return Mono.just(GraphResult.error("Graph 功能未启用"));
        }
        
        return graphManager.buildGraph(projectRoot)
                .map(buildResult -> {
                    int entityCount = buildResult.getEntityCount();
                    int relationCount = buildResult.getRelationCount();
                    
                    return GraphResult.builder()
                            .success(true)
                            .entities(new ArrayList<>()) // 构建时不返回全部实体
                            .relations(new ArrayList<>())
                            .statistics(new HashMap<String, Object>() {{
                                put("entityCount", entityCount);
                                put("relationCount", relationCount);
                            }})
                            .build();
                })
                .onErrorResume(e -> {
                    log.error("构建图谱失败", e);
                    return Mono.just(GraphResult.error(e.getMessage()));
                });
    }
    
    @Override
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
                    var upstreamResult = graphManager.getImpactAnalyzer()
                            .analyzeImpact(query.getEntityId(), 
                                    ImpactAnalyzer.AnalysisType.UPSTREAM, 
                                    query.getMaxDepth()).block();
                    if (upstreamResult != null && upstreamResult.getSuccess()) {
                        entities = convertEntities(upstreamResult.getUpstreamEntities());
                    }
                    break;
                    
                case FIND_DEPENDENTS:
                    // 使用 ImpactAnalyzer 的 DOWNSTREAM 分析（谁依赖我）
                    var downstreamResult = graphManager.getImpactAnalyzer()
                            .analyzeImpact(query.getEntityId(), 
                                    ImpactAnalyzer.AnalysisType.DOWNSTREAM, 
                                    query.getMaxDepth()).block();
                    if (downstreamResult != null && downstreamResult.getSuccess()) {
                        entities = convertEntities(downstreamResult.getDownstreamEntities());
                    }
                    break;
                    
                case IMPACT_ANALYSIS:
                    // 双向影响分析
                    var impactResult = graphManager.getImpactAnalyzer()
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
                    var callees = graphManager.getNavigator()
                            .findCallees(query.getEntityId(), query.getMaxDepth()).block();
                    if (callees != null) {
                        entities = convertEntities(callees);
                    }
                    break;
                    
                case INHERITANCE_TREE:
                    var inheritance = graphManager.getNavigator()
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
                    CodeEntity entity = graphManager.getGraphStore()
                            .getEntity(query.getEntityId()).block();
                    if (entity != null) {
                        entities = List.of(convertEntity(entity));
                    }
                    break;
            }
            
            // 生成 Mermaid 图（如果请求）
            if (query.isGenerateDiagram() && !entities.isEmpty()) {
                String startId = entities.get(0).getId();
                mermaidDiagram = graphManager.getVisualizer()
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
        GraphSearchEngine.GraphSearchResult result = graphManager.getSearchEngine()
                .searchBySymbol(keyword, null, limit).block();
        
        if (result == null || !result.getSuccess()) {
            return new ArrayList<>();
        }
        
        return result.getResults().stream()
                .map(scored -> convertEntity(scored.getEntity()))
                .collect(Collectors.toList());
    }
    
    @Override
    public Mono<Boolean> save() {
        return graphManager.saveGraph();
    }
    
    @Override
    public Mono<Boolean> load(Path storagePath) {
        // GraphManager.loadGraph() 使用配置中的路径，不接受参数
        return graphManager.loadGraph();
    }
    
    @Override
    public boolean isInitialized() {
        return graphManager.isInitialized();
    }
    
    @Override
    public boolean isEnabled() {
        return config.getEnabled();
    }
    
    // ==================== 影响分析实现 ====================
    
    @Override
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
            
            var result = graphManager.getImpactAnalyzer()
                    .analyzeImpact(entityId, type, maxDepth).block();
            
            if (result == null || !result.getSuccess()) {
                return new ImpactAnalysisResultImpl(false, 
                        result != null ? result.getErrorMessage() : "分析失败");
            }
            
            return new ImpactAnalysisResultImpl(result);
        });
    }
    
    @Override
    public Mono<List<EntityInfo>> findCallers(String methodId, int maxDepth) {
        if (!isEnabled() || !isInitialized()) {
            return Mono.just(new ArrayList<>());
        }
        
        return graphManager.getNavigator().findCallers(methodId, maxDepth)
                .map(this::convertToEntityInfoList);
    }
    
    @Override
    public Mono<List<EntityInfo>> findCallees(String methodId, int maxDepth) {
        if (!isEnabled() || !isInitialized()) {
            return Mono.just(new ArrayList<>());
        }
        
        return graphManager.getNavigator().findCallees(methodId, maxDepth)
                .map(this::convertToEntityInfoList);
    }
    
    @Override
    public Mono<List<CallChainInfo>> findCallChains(String sourceMethodId, String targetMethodId, int maxDepth) {
        if (!isEnabled() || !isInitialized()) {
            return Mono.just(new ArrayList<>());
        }
        
        return graphManager.getNavigator().findCallChains(sourceMethodId, targetMethodId, maxDepth)
                .map(chains -> chains.stream()
                        .map(chain -> (CallChainInfo) new CallChainInfoImpl(chain))
                        .collect(Collectors.toList()));
    }
    
    @Override
    public Mono<String> exportCallGraphToMermaid(String methodId, int maxDepth) {
        if (!isEnabled() || !isInitialized()) {
            return Mono.just("");
        }
        
        return graphManager.getVisualizer().exportCallGraphToMermaid(methodId, maxDepth);
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
    
    // ==================== 内部实现类 ====================
    
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
            this.startLine = entity.getStartLine() != null ? entity.getStartLine() : 0;
        }
        
        @Override public String getId() { return id; }
        @Override public String getName() { return name; }
        @Override public String getType() { return type; }
        @Override public String getFilePath() { return filePath; }
        @Override public int getStartLine() { return startLine; }
    }
    
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
}
