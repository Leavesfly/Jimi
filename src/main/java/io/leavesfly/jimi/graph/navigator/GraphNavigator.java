package io.leavesfly.jimi.graph.navigator;

import io.leavesfly.jimi.graph.model.CodeEntity;
import io.leavesfly.jimi.graph.model.CodeRelation;
import io.leavesfly.jimi.graph.model.EntityType;
import io.leavesfly.jimi.graph.model.RelationType;
import io.leavesfly.jimi.graph.store.CodeGraphStore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 图导航器
 * <p>
 * 提供代码图的导航和查询功能,支持多跳推理
 */
@Slf4j
@Component
public class GraphNavigator {
    
    private final CodeGraphStore graphStore;
    
    public GraphNavigator(CodeGraphStore graphStore) {
        this.graphStore = graphStore;
    }
    
    /**
     * 获取实体的所有邻居
     *
     * @param entityId 实体ID
     * @param direction 方向 (OUTGOING/INCOMING/BOTH)
     * @param relationTypes 关系类型过滤 (null表示所有类型)
     * @return 邻居列表
     */
    public Mono<List<CodeEntity>> getNeighbors(String entityId, Direction direction, 
                                               Set<RelationType> relationTypes) {
        return Mono.defer(() -> {
            if (direction == Direction.BOTH) {
                return Mono.zip(
                    getNeighborsByDirection(entityId, true, relationTypes),
                    getNeighborsByDirection(entityId, false, relationTypes)
                ).map(tuple -> {
                    Set<CodeEntity> combined = new HashSet<>(tuple.getT1());
                    combined.addAll(tuple.getT2());
                    return new ArrayList<>(combined);
                });
            } else {
                boolean outgoing = direction == Direction.OUTGOING;
                return getNeighborsByDirection(entityId, outgoing, relationTypes);
            }
        });
    }
    
    /**
     * 多跳查询: 从起始实体出发,根据关系类型进行N跳导航
     *
     * @param startId 起始实体ID
     * @param relationTypes 允许的关系类型
     * @param maxHops 最大跳数
     * @param entityFilter 实体过滤条件
     * @return 导航结果
     */
    public Mono<NavigationResult> multiHopNavigation(String startId, 
                                                     Set<RelationType> relationTypes,
                                                     int maxHops,
                                                     Predicate<CodeEntity> entityFilter) {
        return Mono.fromCallable(() -> {
            NavigationResult result = new NavigationResult();
            result.setStartEntityId(startId);
            result.setMaxHops(maxHops);
            
            // 获取起始实体
            CodeEntity startEntity = graphStore.getEntity(startId).block();
            if (startEntity == null) {
                result.setSuccess(false);
                result.setErrorMessage("Start entity not found: " + startId);
                return result;
            }
            
            // BFS 多跳导航
            Queue<HopNode> queue = new LinkedList<>();
            Set<String> visited = new HashSet<>();
            
            queue.offer(new HopNode(startId, 0, new ArrayList<>()));
            visited.add(startId);
            
            while (!queue.isEmpty()) {
                HopNode current = queue.poll();
                CodeEntity entity = graphStore.getEntity(current.entityId).block();
                
                if (entity != null && entityFilter.test(entity)) {
                    result.addEntity(entity, current.hop);
                }
                
                if (current.hop >= maxHops) {
                    continue;
                }
                
                // 获取邻居 (出边)
                List<CodeRelation> relations = graphStore.getRelationsBySource(current.entityId).block();
                if (relations != null) {
                    for (CodeRelation relation : relations) {
                        if (relationTypes == null || relationTypes.contains(relation.getType())) {
                            String neighborId = relation.getTargetId();
                            if (!visited.contains(neighborId)) {
                                visited.add(neighborId);
                                List<String> newPath = new ArrayList<>(current.path);
                                newPath.add(relation.getType().name());
                                queue.offer(new HopNode(neighborId, current.hop + 1, newPath));
                                result.addRelation(relation);
                            }
                        }
                    }
                }
            }
            
            result.setSuccess(true);
            result.setTotalEntities(result.getEntitiesByHop().values().stream()
                .mapToInt(List::size).sum());
            
            return result;
        });
    }
    
    /**
     * 查找调用链: 找到所有从 fromEntity 到 toEntity 的调用路径
     *
     * @param fromEntityId 起始实体
     * @param toEntityId 目标实体
     * @param maxDepth 最大深度
     * @return 所有调用路径
     */
    public Mono<List<CallChain>> findCallChains(String fromEntityId, String toEntityId, int maxDepth) {
        return Mono.fromCallable(() -> {
            List<CallChain> chains = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            List<CodeEntity> currentPath = new ArrayList<>();
            List<CodeRelation> currentRelations = new ArrayList<>();
            
            dfsCallChain(fromEntityId, toEntityId, maxDepth, 0, 
                        visited, currentPath, currentRelations, chains);
            
            return chains;
        });
    }
    
    /**
     * 查找类的继承层次
     *
     * @param classEntityId 类实体ID
     * @param direction UP (查找父类) / DOWN (查找子类) / BOTH (双向)
     * @return 继承层次结果
     */
    public Mono<InheritanceHierarchy> getInheritanceHierarchy(String classEntityId, Direction direction) {
        return Mono.fromCallable(() -> {
            InheritanceHierarchy hierarchy = new InheritanceHierarchy();
            hierarchy.setRootEntityId(classEntityId);
            
            CodeEntity rootEntity = graphStore.getEntity(classEntityId).block();
            if (rootEntity == null) {
                return hierarchy;
            }
            
            hierarchy.setRootEntity(rootEntity);
            
            if (direction == Direction.OUTGOING || direction == Direction.BOTH) {
                // 查找父类 (EXTENDS 关系的出边)
                List<CodeEntity> parents = new ArrayList<>();
                collectInheritanceChain(classEntityId, RelationType.EXTENDS, true, parents, new HashSet<>());
                hierarchy.setParentClasses(parents);
            }
            
            if (direction == Direction.INCOMING || direction == Direction.BOTH) {
                // 查找子类 (EXTENDS 关系的入边)
                List<CodeEntity> children = new ArrayList<>();
                collectInheritanceChain(classEntityId, RelationType.EXTENDS, false, children, new HashSet<>());
                hierarchy.setChildClasses(children);
            }
            
            // 查找实现的接口
            List<CodeEntity> interfaces = new ArrayList<>();
            collectInheritanceChain(classEntityId, RelationType.IMPLEMENTS, true, interfaces, new HashSet<>());
            hierarchy.setImplementedInterfaces(interfaces);
            
            return hierarchy;
        });
    }
    
    /**
     * 查找某个方法的所有调用者
     *
     * @param methodEntityId 方法实体ID
     * @param maxDepth 最大深度
     * @return 调用者列表
     */
    public Mono<List<CodeEntity>> findCallers(String methodEntityId, int maxDepth) {
        return graphStore.bfs(methodEntityId, 
            entity -> entity.getType() == EntityType.METHOD || 
                     entity.getType() == EntityType.CONSTRUCTOR,
            maxDepth)
            .map(entities -> entities.stream()
                .filter(e -> !e.getId().equals(methodEntityId))
                .collect(Collectors.toList()));
    }
    
    /**
     * 查找某个方法调用的所有方法
     *
     * @param methodEntityId 方法实体ID
     * @param maxDepth 最大深度
     * @return 被调用方法列表
     */
    public Mono<List<CodeEntity>> findCallees(String methodEntityId, int maxDepth) {
        return graphStore.getNeighbors(methodEntityId, RelationType.CALLS, true)
            .map(neighbors -> neighbors.stream()
                .filter(e -> e.getType() == EntityType.METHOD || 
                           e.getType() == EntityType.CONSTRUCTOR)
                .collect(Collectors.toList()));
    }
    
    // ==================== 私有辅助方法 ====================
    
    private Mono<List<CodeEntity>> getNeighborsByDirection(String entityId, boolean outgoing, 
                                                           Set<RelationType> relationTypes) {
        return (outgoing ? 
            graphStore.getRelationsBySource(entityId) : 
            graphStore.getRelationsByTarget(entityId))
            .map(relations -> relations.stream()
                .filter(rel -> relationTypes == null || relationTypes.contains(rel.getType()))
                .map(rel -> outgoing ? rel.getTargetId() : rel.getSourceId())
                .distinct()
                .map(id -> graphStore.getEntity(id).block())
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
    }
    
    private void dfsCallChain(String currentId, String targetId, int maxDepth, int currentDepth,
                             Set<String> visited, List<CodeEntity> currentPath, 
                             List<CodeRelation> currentRelations, List<CallChain> result) {
        if (currentDepth > maxDepth) {
            return;
        }
        
        CodeEntity current = graphStore.getEntity(currentId).block();
        if (current == null) {
            return;
        }
        
        visited.add(currentId);
        currentPath.add(current);
        
        if (currentId.equals(targetId)) {
            // 找到目标,保存路径
            CallChain chain = new CallChain();
            chain.setPath(new ArrayList<>(currentPath));
            chain.setRelations(new ArrayList<>(currentRelations));
            chain.setDepth(currentDepth);
            result.add(chain);
        } else {
            // 继续搜索
            List<CodeRelation> relations = graphStore.getRelationsBySource(currentId).block();
            if (relations != null) {
                for (CodeRelation relation : relations) {
                    if (relation.getType() == RelationType.CALLS && 
                        !visited.contains(relation.getTargetId())) {
                        currentRelations.add(relation);
                        dfsCallChain(relation.getTargetId(), targetId, maxDepth, currentDepth + 1,
                                   visited, currentPath, currentRelations, result);
                        currentRelations.remove(currentRelations.size() - 1);
                    }
                }
            }
        }
        
        currentPath.remove(currentPath.size() - 1);
        visited.remove(currentId);
    }
    
    private void collectInheritanceChain(String entityId, RelationType relationType, 
                                        boolean outgoing, List<CodeEntity> result, Set<String> visited) {
        if (visited.contains(entityId)) {
            return;
        }
        visited.add(entityId);
        
        List<CodeRelation> relations = outgoing ?
            graphStore.getRelationsBySource(entityId).block() :
            graphStore.getRelationsByTarget(entityId).block();
        
        if (relations != null) {
            for (CodeRelation relation : relations) {
                if (relation.getType() == relationType) {
                    String nextId = outgoing ? relation.getTargetId() : relation.getSourceId();
                    CodeEntity entity = graphStore.getEntity(nextId).block();
                    if (entity != null) {
                        result.add(entity);
                        collectInheritanceChain(nextId, relationType, outgoing, result, visited);
                    }
                }
            }
        }
    }
    
    // ==================== 数据模型 ====================
    
    /**
     * 导航方向
     */
    public enum Direction {
        OUTGOING,   // 出边
        INCOMING,   // 入边
        BOTH        // 双向
    }
    
    /**
     * 导航结果
     */
    @Data
    public static class NavigationResult {
        private String startEntityId;
        private Integer maxHops;
        private Boolean success;
        private String errorMessage;
        private Integer totalEntities = 0;
        
        // 按跳数分组的实体
        private Map<Integer, List<CodeEntity>> entitiesByHop = new HashMap<>();
        
        // 涉及的关系
        private List<CodeRelation> relations = new ArrayList<>();
        
        public void addEntity(CodeEntity entity, int hop) {
            entitiesByHop.computeIfAbsent(hop, k -> new ArrayList<>()).add(entity);
        }
        
        public void addRelation(CodeRelation relation) {
            relations.add(relation);
        }
    }
    
    /**
     * 调用链
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallChain {
        private List<CodeEntity> path;
        private List<CodeRelation> relations;
        private Integer depth;
        
        public String getPathString() {
            if (path == null || path.isEmpty()) {
                return "";
            }
            return path.stream()
                .map(CodeEntity::getName)
                .collect(Collectors.joining(" -> "));
        }
    }
    
    /**
     * 继承层次
     */
    @Data
    public static class InheritanceHierarchy {
        private String rootEntityId;
        private CodeEntity rootEntity;
        private List<CodeEntity> parentClasses = new ArrayList<>();
        private List<CodeEntity> childClasses = new ArrayList<>();
        private List<CodeEntity> implementedInterfaces = new ArrayList<>();
        
        public int getTotalNodes() {
            return 1 + parentClasses.size() + childClasses.size() + implementedInterfaces.size();
        }
    }
    
    /**
     * 跳数节点 (用于BFS)
     */
    private static class HopNode {
        String entityId;
        int hop;
        List<String> path; // 关系类型路径
        
        HopNode(String entityId, int hop, List<String> path) {
            this.entityId = entityId;
            this.hop = hop;
            this.path = path;
        }
    }
}
