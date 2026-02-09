package io.leavesfly.jimi.adk.knowledge.graph.navigator;

import io.leavesfly.jimi.adk.knowledge.graph.model.CodeEntity;
import io.leavesfly.jimi.adk.knowledge.graph.model.CodeRelation;
import io.leavesfly.jimi.adk.knowledge.graph.model.EntityType;
import io.leavesfly.jimi.adk.knowledge.graph.model.RelationType;
import io.leavesfly.jimi.adk.knowledge.graph.store.CodeGraphStore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 影响分析器
 * <p>
 * 分析代码变更的影响范围,支持正向和反向依赖分析
 */
@Slf4j
public class ImpactAnalyzer {
    
    private final CodeGraphStore graphStore;
    
    public ImpactAnalyzer(CodeGraphStore graphStore) {
        this.graphStore = graphStore;
    }
    
    /**
     * 分析修改某个实体的影响范围
     *
     * @param entityId 实体ID
     * @param analysisType 分析类型 (DOWNSTREAM/UPSTREAM/BOTH)
     * @param maxDepth 最大深度
     * @return 影响分析结果
     */
    public Mono<ImpactAnalysisResult> analyzeImpact(String entityId, 
                                                    AnalysisType analysisType,
                                                    int maxDepth) {
        return Mono.fromCallable(() -> {
            ImpactAnalysisResult result = new ImpactAnalysisResult();
            result.setTargetEntityId(entityId);
            result.setAnalysisType(analysisType);
            result.setMaxDepth(maxDepth);
            
            CodeEntity targetEntity = graphStore.getEntity(entityId).block();
            if (targetEntity == null) {
                result.setSuccess(false);
                result.setErrorMessage("Entity not found: " + entityId);
                return result;
            }
            
            result.setTargetEntity(targetEntity);
            
            // 下游影响分析 (谁依赖我)
            if (analysisType == AnalysisType.DOWNSTREAM || analysisType == AnalysisType.BOTH) {
                Set<CodeEntity> downstreamEntities = new HashSet<>();
                Set<CodeRelation> downstreamRelations = new HashSet<>();
                analyzeDownstream(entityId, maxDepth, 0, downstreamEntities, 
                                downstreamRelations, new HashSet<>());
                result.setDownstreamEntities(new ArrayList<>(downstreamEntities));
                result.setDownstreamRelations(new ArrayList<>(downstreamRelations));
            }
            
            // 上游依赖分析 (我依赖谁)
            if (analysisType == AnalysisType.UPSTREAM || analysisType == AnalysisType.BOTH) {
                Set<CodeEntity> upstreamEntities = new HashSet<>();
                Set<CodeRelation> upstreamRelations = new HashSet<>();
                analyzeUpstream(entityId, maxDepth, 0, upstreamEntities, 
                              upstreamRelations, new HashSet<>());
                result.setUpstreamEntities(new ArrayList<>(upstreamEntities));
                result.setUpstreamRelations(new ArrayList<>(upstreamRelations));
            }
            
            result.setSuccess(true);
            result.calculateStatistics();
            
            log.info("Impact analysis completed for {}: {} downstream, {} upstream", 
                    entityId, 
                    result.getDownstreamEntities().size(),
                    result.getUpstreamEntities().size());
            
            return result;
        });
    }
    
    /**
     * 分析修改某个文件的影响范围
     *
     * @param filePath 文件路径
     * @param maxDepth 最大深度
     * @return 影响分析结果
     */
    public Mono<FileImpactResult> analyzeFileImpact(String filePath, int maxDepth) {
        return graphStore.getEntitiesByFile(filePath)
            .flatMap(entities -> {
                FileImpactResult result = new FileImpactResult();
                result.setFilePath(filePath);
                result.setEntitiesInFile(entities);
                
                Set<CodeEntity> allAffectedEntities = new HashSet<>();
                Set<String> affectedFiles = new HashSet<>();
                
                // 对文件中的每个实体进行影响分析
                for (CodeEntity entity : entities) {
                    ImpactAnalysisResult entityImpact = 
                        analyzeImpact(entity.getId(), AnalysisType.DOWNSTREAM, maxDepth).block();
                    
                    if (entityImpact != null && entityImpact.getSuccess()) {
                        allAffectedEntities.addAll(entityImpact.getDownstreamEntities());
                        
                        // 收集受影响的文件
                        for (CodeEntity affected : entityImpact.getDownstreamEntities()) {
                            if (affected.getFilePath() != null && 
                                !affected.getFilePath().equals(filePath)) {
                                affectedFiles.add(affected.getFilePath());
                            }
                        }
                    }
                }
                
                result.setAffectedEntities(new ArrayList<>(allAffectedEntities));
                result.setAffectedFiles(new ArrayList<>(affectedFiles));
                result.calculateStatistics();
                
                return Mono.just(result);
            });
    }
    
    /**
     * 分析方法调用影响
     *
     * @param methodEntityId 方法实体ID
     * @param maxDepth 最大深度
     * @return 方法调用影响结果
     */
    public Mono<MethodCallImpact> analyzeMethodCallImpact(String methodEntityId, int maxDepth) {
        return Mono.fromCallable(() -> {
            MethodCallImpact result = new MethodCallImpact();
            result.setMethodEntityId(methodEntityId);
            
            CodeEntity method = graphStore.getEntity(methodEntityId).block();
            if (method == null || method.getType() != EntityType.METHOD) {
                return result;
            }
            
            result.setMethod(method);
            
            // 查找所有调用该方法的方法 (直接调用者)
            List<CodeRelation> callers = graphStore.getRelationsByTarget(methodEntityId).block();
            if (callers != null) {
                List<CodeEntity> directCallers = callers.stream()
                    .filter(rel -> rel.getType() == RelationType.CALLS)
                    .map(rel -> graphStore.getEntity(rel.getSourceId()).block())
                    .filter(Objects::nonNull)
                    .filter(e -> e.getType() == EntityType.METHOD || 
                               e.getType() == EntityType.CONSTRUCTOR)
                    .collect(Collectors.toList());
                
                result.setDirectCallers(directCallers);
                
                // 递归查找间接调用者
                Set<CodeEntity> indirectCallers = new HashSet<>();
                for (CodeEntity caller : directCallers) {
                    findIndirectCallers(caller.getId(), maxDepth - 1, indirectCallers, new HashSet<>());
                }
                indirectCallers.removeAll(directCallers); // 移除直接调用者
                result.setIndirectCallers(new ArrayList<>(indirectCallers));
            }
            
            // 查找该方法调用的所有方法 (直接被调用者)
            List<CodeRelation> callees = graphStore.getRelationsBySource(methodEntityId).block();
            if (callees != null) {
                List<CodeEntity> directCallees = callees.stream()
                    .filter(rel -> rel.getType() == RelationType.CALLS)
                    .map(rel -> graphStore.getEntity(rel.getTargetId()).block())
                    .filter(Objects::nonNull)
                    .filter(e -> e.getType() == EntityType.METHOD || 
                               e.getType() == EntityType.CONSTRUCTOR)
                    .collect(Collectors.toList());
                
                result.setDirectCallees(directCallees);
            }
            
            result.calculateStatistics();
            
            return result;
        });
    }
    
    /**
     * 分析类继承影响
     *
     * @param classEntityId 类实体ID
     * @return 类继承影响结果
     */
    public Mono<ClassInheritanceImpact> analyzeClassInheritanceImpact(String classEntityId) {
        return Mono.fromCallable(() -> {
            ClassInheritanceImpact result = new ClassInheritanceImpact();
            result.setClassEntityId(classEntityId);
            
            CodeEntity classEntity = graphStore.getEntity(classEntityId).block();
            if (classEntity == null || 
                (classEntity.getType() != EntityType.CLASS && 
                 classEntity.getType() != EntityType.INTERFACE)) {
                return result;
            }
            
            result.setClassEntity(classEntity);
            
            // 查找父类
            List<CodeRelation> extendsRelations = graphStore.getRelationsBySource(classEntityId).block();
            if (extendsRelations != null) {
                List<CodeEntity> parents = extendsRelations.stream()
                    .filter(rel -> rel.getType() == RelationType.EXTENDS)
                    .map(rel -> graphStore.getEntity(rel.getTargetId()).block())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
                result.setParentClasses(parents);
            }
            
            // 查找子类
            List<CodeRelation> extendedByRelations = graphStore.getRelationsByTarget(classEntityId).block();
            if (extendedByRelations != null) {
                List<CodeEntity> children = extendedByRelations.stream()
                    .filter(rel -> rel.getType() == RelationType.EXTENDS)
                    .map(rel -> graphStore.getEntity(rel.getSourceId()).block())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
                result.setChildClasses(children);
            }
            
            // 查找实现的接口
            if (extendsRelations != null) {
                List<CodeEntity> interfaces = extendsRelations.stream()
                    .filter(rel -> rel.getType() == RelationType.IMPLEMENTS)
                    .map(rel -> graphStore.getEntity(rel.getTargetId()).block())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
                result.setImplementedInterfaces(interfaces);
            }
            
            // 查找实现该接口的类
            if (classEntity.getType() == EntityType.INTERFACE && extendedByRelations != null) {
                List<CodeEntity> implementers = extendedByRelations.stream()
                    .filter(rel -> rel.getType() == RelationType.IMPLEMENTS)
                    .map(rel -> graphStore.getEntity(rel.getSourceId()).block())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
                result.setImplementingClasses(implementers);
            }
            
            return result;
        });
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 递归分析下游影响 (谁依赖我)
     */
    private void analyzeDownstream(String entityId, int maxDepth, int currentDepth,
                                   Set<CodeEntity> entities, Set<CodeRelation> relations,
                                   Set<String> visited) {
        if (currentDepth >= maxDepth || visited.contains(entityId)) {
            return;
        }
        
        visited.add(entityId);
        
        // 获取所有指向该实体的关系 (入边)
        List<CodeRelation> incomingRelations = graphStore.getRelationsByTarget(entityId).block();
        
        if (incomingRelations != null) {
            for (CodeRelation relation : incomingRelations) {
                // 过滤相关的依赖关系类型
                if (isDependencyRelation(relation.getType())) {
                    relations.add(relation);
                    
                    CodeEntity dependent = graphStore.getEntity(relation.getSourceId()).block();
                    if (dependent != null && !entities.contains(dependent)) {
                        entities.add(dependent);
                        analyzeDownstream(dependent.getId(), maxDepth, currentDepth + 1,
                                        entities, relations, visited);
                    }
                }
            }
        }
    }
    
    /**
     * 递归分析上游依赖 (我依赖谁)
     */
    private void analyzeUpstream(String entityId, int maxDepth, int currentDepth,
                                Set<CodeEntity> entities, Set<CodeRelation> relations,
                                Set<String> visited) {
        if (currentDepth >= maxDepth || visited.contains(entityId)) {
            return;
        }
        
        visited.add(entityId);
        
        // 获取该实体的所有关系 (出边)
        List<CodeRelation> outgoingRelations = graphStore.getRelationsBySource(entityId).block();
        
        if (outgoingRelations != null) {
            for (CodeRelation relation : outgoingRelations) {
                if (isDependencyRelation(relation.getType())) {
                    relations.add(relation);
                    
                    CodeEntity dependency = graphStore.getEntity(relation.getTargetId()).block();
                    if (dependency != null && !entities.contains(dependency)) {
                        entities.add(dependency);
                        analyzeUpstream(dependency.getId(), maxDepth, currentDepth + 1,
                                      entities, relations, visited);
                    }
                }
            }
        }
    }
    
    /**
     * 查找间接调用者
     */
    private void findIndirectCallers(String methodId, int remainingDepth,
                                     Set<CodeEntity> result, Set<String> visited) {
        if (remainingDepth <= 0 || visited.contains(methodId)) {
            return;
        }
        
        visited.add(methodId);
        
        List<CodeRelation> callers = graphStore.getRelationsByTarget(methodId).block();
        if (callers != null) {
            for (CodeRelation rel : callers) {
                if (rel.getType() == RelationType.CALLS) {
                    CodeEntity caller = graphStore.getEntity(rel.getSourceId()).block();
                    if (caller != null && 
                        (caller.getType() == EntityType.METHOD || 
                         caller.getType() == EntityType.CONSTRUCTOR)) {
                        result.add(caller);
                        findIndirectCallers(caller.getId(), remainingDepth - 1, result, visited);
                    }
                }
            }
        }
    }
    
    /**
     * 判断是否为依赖关系
     */
    private boolean isDependencyRelation(RelationType type) {
        return type == RelationType.CALLS ||
               type == RelationType.REFERENCES ||
               type == RelationType.EXTENDS ||
               type == RelationType.IMPLEMENTS ||
               type == RelationType.USES_TYPE;
    }
    
    // ==================== 数据模型 ====================
    
    /**
     * 分析类型
     */
    public enum AnalysisType {
        DOWNSTREAM,  // 下游影响 (谁依赖我)
        UPSTREAM,    // 上游依赖 (我依赖谁)
        BOTH         // 双向分析
    }
    
    /**
     * 影响分析结果
     */
    @Data
    public static class ImpactAnalysisResult {
        private String targetEntityId;
        private CodeEntity targetEntity;
        private AnalysisType analysisType;
        private Integer maxDepth;
        private Boolean success;
        private String errorMessage;
        
        // 下游影响 (依赖我的实体)
        private List<CodeEntity> downstreamEntities = new ArrayList<>();
        private List<CodeRelation> downstreamRelations = new ArrayList<>();
        
        // 上游依赖 (我依赖的实体)
        private List<CodeEntity> upstreamEntities = new ArrayList<>();
        private List<CodeRelation> upstreamRelations = new ArrayList<>();
        
        // 统计信息
        private Map<EntityType, Integer> downstreamByType = new HashMap<>();
        private Map<EntityType, Integer> upstreamByType = new HashMap<>();
        
        public void calculateStatistics() {
            // 统计下游实体类型分布
            for (CodeEntity entity : downstreamEntities) {
                downstreamByType.merge(entity.getType(), 1, Integer::sum);
            }
            
            // 统计上游实体类型分布
            for (CodeEntity entity : upstreamEntities) {
                upstreamByType.merge(entity.getType(), 1, Integer::sum);
            }
        }
        
        public int getTotalImpactedEntities() {
            return downstreamEntities.size() + upstreamEntities.size();
        }
    }
    
    /**
     * 文件影响结果
     */
    @Data
    public static class FileImpactResult {
        private String filePath;
        private List<CodeEntity> entitiesInFile = new ArrayList<>();
        private List<CodeEntity> affectedEntities = new ArrayList<>();
        private List<String> affectedFiles = new ArrayList<>();
        
        // 统计信息
        private Integer totalAffectedEntities;
        private Integer totalAffectedFiles;
        
        public void calculateStatistics() {
            totalAffectedEntities = affectedEntities.size();
            totalAffectedFiles = affectedFiles.size();
        }
    }
    
    /**
     * 方法调用影响
     */
    @Data
    public static class MethodCallImpact {
        private String methodEntityId;
        private CodeEntity method;
        
        // 调用该方法的方法
        private List<CodeEntity> directCallers = new ArrayList<>();
        private List<CodeEntity> indirectCallers = new ArrayList<>();
        
        // 该方法调用的方法
        private List<CodeEntity> directCallees = new ArrayList<>();
        
        // 统计信息
        private Integer totalCallers;
        private Integer totalCallees;
        
        public void calculateStatistics() {
            totalCallers = directCallers.size() + indirectCallers.size();
            totalCallees = directCallees.size();
        }
    }
    
    /**
     * 类继承影响
     */
    @Data
    public static class ClassInheritanceImpact {
        private String classEntityId;
        private CodeEntity classEntity;
        
        private List<CodeEntity> parentClasses = new ArrayList<>();
        private List<CodeEntity> childClasses = new ArrayList<>();
        private List<CodeEntity> implementedInterfaces = new ArrayList<>();
        private List<CodeEntity> implementingClasses = new ArrayList<>();
    }
}
