package io.leavesfly.jimi.graph.navigator;

import io.leavesfly.jimi.graph.model.CodeEntity;
import io.leavesfly.jimi.graph.model.CodeRelation;
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
import java.util.stream.Collectors;

/**
 * 路径查找器
 * <p>
 * 提供多种路径查找算法,支持最短路径、所有路径等
 */
@Slf4j
@Component
public class PathFinder {
    
    private final CodeGraphStore graphStore;
    
    public PathFinder(CodeGraphStore graphStore) {
        this.graphStore = graphStore;
    }
    
    /**
     * 查找最短路径 (BFS)
     *
     * @param fromId 起始实体ID
     * @param toId 目标实体ID
     * @param relationTypes 允许的关系类型 (null表示所有类型)
     * @param maxHops 最大跳数
     * @return 最短路径
     */
    public Mono<PathResult> findShortestPath(String fromId, String toId,
                                             Set<RelationType> relationTypes,
                                             int maxHops) {
        return Mono.fromCallable(() -> {
            PathResult result = new PathResult();
            result.setFromEntityId(fromId);
            result.setToEntityId(toId);
            result.setSearchType(SearchType.SHORTEST);
            
            // BFS 查找最短路径
            Queue<PathNode> queue = new LinkedList<>();
            Map<String, PathNode> visited = new HashMap<>();
            
            PathNode start = new PathNode(fromId, null, null, 0);
            queue.offer(start);
            visited.put(fromId, start);
            
            while (!queue.isEmpty()) {
                PathNode current = queue.poll();
                
                if (current.entityId.equals(toId)) {
                    // 找到目标,回溯构建路径
                    result.setPath(buildPath(current));
                    result.setSuccess(true);
                    return result;
                }
                
                if (current.depth >= maxHops) {
                    continue;
                }
                
                // 遍历邻居
                List<CodeRelation> relations = graphStore.getRelationsBySource(current.entityId).block();
                if (relations != null) {
                    for (CodeRelation relation : relations) {
                        if (relationTypes == null || relationTypes.contains(relation.getType())) {
                            String neighborId = relation.getTargetId();
                            
                            if (!visited.containsKey(neighborId)) {
                                PathNode neighbor = new PathNode(neighborId, current, relation, current.depth + 1);
                                queue.offer(neighbor);
                                visited.put(neighborId, neighbor);
                            }
                        }
                    }
                }
            }
            
            result.setSuccess(false);
            result.setErrorMessage("No path found");
            return result;
        });
    }
    
    /**
     * 查找所有路径 (DFS)
     *
     * @param fromId 起始实体ID
     * @param toId 目标实体ID
     * @param relationTypes 允许的关系类型
     * @param maxHops 最大跳数
     * @param maxPaths 最大路径数量
     * @return 所有路径结果
     */
    public Mono<MultiPathResult> findAllPaths(String fromId, String toId,
                                              Set<RelationType> relationTypes,
                                              int maxHops, int maxPaths) {
        return Mono.fromCallable(() -> {
            MultiPathResult result = new MultiPathResult();
            result.setFromEntityId(fromId);
            result.setToEntityId(toId);
            result.setSearchType(SearchType.ALL_PATHS);
            result.setMaxPaths(maxPaths);
            
            List<Path> paths = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            List<CodeEntity> currentPath = new ArrayList<>();
            List<CodeRelation> currentRelations = new ArrayList<>();
            
            dfsAllPaths(fromId, toId, relationTypes, maxHops, 0, maxPaths,
                       visited, currentPath, currentRelations, paths);
            
            result.setPaths(paths);
            result.setSuccess(!paths.isEmpty());
            if (paths.isEmpty()) {
                result.setErrorMessage("No paths found");
            }
            
            return result;
        });
    }
    
    /**
     * 查找K条最短路径
     *
     * @param fromId 起始实体ID
     * @param toId 目标实体ID
     * @param relationTypes 允许的关系类型
     * @param maxHops 最大跳数
     * @param k 路径数量
     * @return K条最短路径
     */
    public Mono<MultiPathResult> findKShortestPaths(String fromId, String toId,
                                                    Set<RelationType> relationTypes,
                                                    int maxHops, int k) {
        return findAllPaths(fromId, toId, relationTypes, maxHops, k * 2)
            .map(result -> {
                // 按路径长度排序,取前K条
                List<Path> sortedPaths = result.getPaths().stream()
                    .sorted(Comparator.comparingInt(Path::getLength))
                    .limit(k)
                    .collect(Collectors.toList());
                
                result.setPaths(sortedPaths);
                result.setSearchType(SearchType.K_SHORTEST);
                return result;
            });
    }
    
    /**
     * 查找两个实体之间的所有中间节点
     *
     * @param fromId 起始实体ID
     * @param toId 目标实体ID
     * @param maxHops 最大跳数
     * @return 中间节点集合
     */
    public Mono<Set<CodeEntity>> findIntermediateNodes(String fromId, String toId, int maxHops) {
        return findAllPaths(fromId, toId, null, maxHops, 100)
            .map(result -> {
                Set<CodeEntity> intermediates = new HashSet<>();
                
                for (Path path : result.getPaths()) {
                    List<CodeEntity> entities = path.getEntities();
                    // 排除起始和终止节点
                    for (int i = 1; i < entities.size() - 1; i++) {
                        intermediates.add(entities.get(i));
                    }
                }
                
                return intermediates;
            });
    }
    
    /**
     * 查找循环依赖
     *
     * @param entityId 实体ID
     * @param relationTypes 关系类型
     * @param maxDepth 最大深度
     * @return 循环依赖结果
     */
    public Mono<List<Path>> findCycles(String entityId, 
                                       Set<RelationType> relationTypes,
                                       int maxDepth) {
        return Mono.fromCallable(() -> {
            List<Path> cycles = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            List<String> currentPath = new ArrayList<>();
            
            dfsCycles(entityId, entityId, relationTypes, maxDepth, 0,
                     visited, currentPath, cycles);
            
            return cycles;
        });
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * DFS 查找所有路径
     */
    private void dfsAllPaths(String currentId, String targetId,
                            Set<RelationType> relationTypes,
                            int maxHops, int currentDepth, int maxPaths,
                            Set<String> visited, List<CodeEntity> currentPath,
                            List<CodeRelation> currentRelations, List<Path> result) {
        if (result.size() >= maxPaths || currentDepth > maxHops) {
            return;
        }
        
        CodeEntity current = graphStore.getEntity(currentId).block();
        if (current == null) {
            return;
        }
        
        visited.add(currentId);
        currentPath.add(current);
        
        if (currentId.equals(targetId) && currentDepth > 0) {
            // 找到目标,保存路径
            Path path = Path.builder()
                .entities(new ArrayList<>(currentPath))
                .relations(new ArrayList<>(currentRelations))
                .length(currentDepth)
                .build();
            result.add(path);
        } else {
            // 继续搜索
            List<CodeRelation> relations = graphStore.getRelationsBySource(currentId).block();
            if (relations != null) {
                for (CodeRelation relation : relations) {
                    if ((relationTypes == null || relationTypes.contains(relation.getType())) &&
                        !visited.contains(relation.getTargetId())) {
                        
                        currentRelations.add(relation);
                        dfsAllPaths(relation.getTargetId(), targetId, relationTypes,
                                  maxHops, currentDepth + 1, maxPaths,
                                  visited, currentPath, currentRelations, result);
                        currentRelations.remove(currentRelations.size() - 1);
                    }
                }
            }
        }
        
        currentPath.remove(currentPath.size() - 1);
        visited.remove(currentId);
    }
    
    /**
     * DFS 查找循环依赖
     */
    private void dfsCycles(String startId, String currentId,
                          Set<RelationType> relationTypes,
                          int maxDepth, int currentDepth,
                          Set<String> visited, List<String> currentPath,
                          List<Path> result) {
        if (currentDepth > maxDepth) {
            return;
        }
        
        currentPath.add(currentId);
        
        List<CodeRelation> relations = graphStore.getRelationsBySource(currentId).block();
        if (relations != null) {
            for (CodeRelation relation : relations) {
                if (relationTypes == null || relationTypes.contains(relation.getType())) {
                    String nextId = relation.getTargetId();
                    
                    if (nextId.equals(startId) && currentDepth > 0) {
                        // 找到循环
                        List<CodeEntity> cycleEntities = new ArrayList<>();
                        for (String entityId : currentPath) {
                            CodeEntity entity = graphStore.getEntity(entityId).block();
                            if (entity != null) {
                                cycleEntities.add(entity);
                            }
                        }
                        
                        Path cycle = Path.builder()
                            .entities(cycleEntities)
                            .length(currentPath.size())
                            .build();
                        result.add(cycle);
                    } else if (!visited.contains(nextId)) {
                        visited.add(nextId);
                        dfsCycles(startId, nextId, relationTypes, maxDepth, currentDepth + 1,
                                visited, currentPath, result);
                        visited.remove(nextId);
                    }
                }
            }
        }
        
        currentPath.remove(currentPath.size() - 1);
    }
    
    /**
     * 从PathNode回溯构建路径
     */
    private Path buildPath(PathNode endNode) {
        List<CodeEntity> entities = new ArrayList<>();
        List<CodeRelation> relations = new ArrayList<>();
        
        PathNode current = endNode;
        while (current != null) {
            CodeEntity entity = graphStore.getEntity(current.entityId).block();
            if (entity != null) {
                entities.add(0, entity);
            }
            if (current.relation != null) {
                relations.add(0, current.relation);
            }
            current = current.parent;
        }
        
        return Path.builder()
            .entities(entities)
            .relations(relations)
            .length(endNode.depth)
            .build();
    }
    
    // ==================== 数据模型 ====================
    
    /**
     * 搜索类型
     */
    public enum SearchType {
        SHORTEST,    // 最短路径
        ALL_PATHS,   // 所有路径
        K_SHORTEST   // K条最短路径
    }
    
    /**
     * 路径节点 (用于BFS)
     */
    private static class PathNode {
        String entityId;
        PathNode parent;
        CodeRelation relation;
        int depth;
        
        PathNode(String entityId, PathNode parent, CodeRelation relation, int depth) {
            this.entityId = entityId;
            this.parent = parent;
            this.relation = relation;
            this.depth = depth;
        }
    }
    
    /**
     * 路径
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Path {
        private List<CodeEntity> entities;
        private List<CodeRelation> relations;
        private Integer length;
        
        public String getPathString() {
            if (entities == null || entities.isEmpty()) {
                return "";
            }
            return entities.stream()
                .map(CodeEntity::getName)
                .collect(Collectors.joining(" -> "));
        }
        
        public String getPathWithTypes() {
            if (entities == null || entities.isEmpty()) {
                return "";
            }
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < entities.size(); i++) {
                sb.append(entities.get(i).getName());
                if (i < relations.size()) {
                    sb.append(" -[").append(relations.get(i).getType()).append("]-> ");
                }
            }
            return sb.toString();
        }
    }
    
    /**
     * 单路径结果
     */
    @Data
    public static class PathResult {
        private String fromEntityId;
        private String toEntityId;
        private SearchType searchType;
        private Boolean success;
        private String errorMessage;
        private Path path;
    }
    
    /**
     * 多路径结果
     */
    @Data
    public static class MultiPathResult {
        private String fromEntityId;
        private String toEntityId;
        private SearchType searchType;
        private Integer maxPaths;
        private Boolean success;
        private String errorMessage;
        private List<Path> paths = new ArrayList<>();
        
        public Path getShortestPath() {
            return paths.stream()
                .min(Comparator.comparingInt(Path::getLength))
                .orElse(null);
        }
        
        public Path getLongestPath() {
            return paths.stream()
                .max(Comparator.comparingInt(Path::getLength))
                .orElse(null);
        }
    }
}
