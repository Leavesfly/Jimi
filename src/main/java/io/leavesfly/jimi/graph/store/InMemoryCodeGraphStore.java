package io.leavesfly.jimi.graph.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.graph.model.CodeEntity;
import io.leavesfly.jimi.graph.model.CodeRelation;
import io.leavesfly.jimi.graph.model.EntityType;
import io.leavesfly.jimi.graph.model.RelationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 基于内存的代码图存储实现
 * <p>
 * 使用 HashMap 和邻接表存储图结构
 * 适合中小型项目 (< 50K 实体)
 */
@Slf4j
@Component
public class InMemoryCodeGraphStore implements CodeGraphStore {
    
    @Autowired(required = false)
    private ObjectMapper objectMapper;
    
    // 图存储路径
    private Path graphPath;
    
    // 实体存储: entityId -> CodeEntity
    private final Map<String, CodeEntity> entities = new ConcurrentHashMap<>();
    
    // 关系存储: relationId -> CodeRelation
    private final Map<String, CodeRelation> relations = new ConcurrentHashMap<>();
    
    // 出边邻接表: sourceId -> List<relationId>
    private final Map<String, List<String>> outgoingEdges = new ConcurrentHashMap<>();
    
    // 入边邻接表: targetId -> List<relationId>
    private final Map<String, List<String>> incomingEdges = new ConcurrentHashMap<>();
    
    // 文件索引: filePath -> List<entityId>
    private final Map<String, List<String>> fileIndex = new ConcurrentHashMap<>();
    
    // ==================== 实体操作 ====================
    
    @Override
    public Mono<Void> addEntity(CodeEntity entity) {
        return Mono.fromRunnable(() -> {
            entities.put(entity.getId(), entity);
            
            // 更新文件索引
            fileIndex.computeIfAbsent(entity.getFilePath(), k -> new ArrayList<>())
                .add(entity.getId());
            
            log.debug("Added entity: {}", entity.getDescription());
        });
    }
    
    @Override
    public Mono<Integer> addEntities(List<CodeEntity> entityList) {
        return Mono.fromCallable(() -> {
            int count = 0;
            for (CodeEntity entity : entityList) {
                entities.put(entity.getId(), entity);
                fileIndex.computeIfAbsent(entity.getFilePath(), k -> new ArrayList<>())
                    .add(entity.getId());
                count++;
            }
            log.info("Added {} entities to graph", count);
            return count;
        });
    }
    
    @Override
    public Mono<CodeEntity> getEntity(String id) {
        return Mono.justOrEmpty(entities.get(id));
    }
    
    @Override
    public Mono<List<CodeEntity>> getEntitiesByType(EntityType type) {
        return Mono.fromCallable(() -> 
            entities.values().stream()
                .filter(entity -> entity.getType() == type)
                .collect(Collectors.toList())
        );
    }
    
    @Override
    public Mono<List<CodeEntity>> getEntitiesByFile(String filePath) {
        return Mono.fromCallable(() -> {
            List<String> entityIds = fileIndex.get(filePath);
            if (entityIds == null) {
                return Collections.emptyList();
            }
            return entityIds.stream()
                .map(entities::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        });
    }
    
    @Override
    public Mono<Void> deleteEntity(String id) {
        return Mono.fromRunnable(() -> {
            CodeEntity entity = entities.remove(id);
            if (entity != null) {
                // 从文件索引中删除
                List<String> fileEntities = fileIndex.get(entity.getFilePath());
                if (fileEntities != null) {
                    fileEntities.remove(id);
                }
                log.debug("Deleted entity: {}", id);
            }
        });
    }
    
    @Override
    public Mono<Integer> deleteEntitiesByFile(String filePath) {
        return Mono.fromCallable(() -> {
            List<String> entityIds = fileIndex.remove(filePath);
            if (entityIds == null) {
                return 0;
            }
            
            int count = 0;
            for (String entityId : entityIds) {
                if (entities.remove(entityId) != null) {
                    count++;
                }
            }
            
            log.info("Deleted {} entities from file: {}", count, filePath);
            return count;
        });
    }
    
    // ==================== 关系操作 ====================
    
    @Override
    public Mono<Void> addRelation(CodeRelation relation) {
        return Mono.fromRunnable(() -> {
            relations.put(relation.getId(), relation);
            
            // 更新邻接表
            outgoingEdges.computeIfAbsent(relation.getSourceId(), k -> new ArrayList<>())
                .add(relation.getId());
            incomingEdges.computeIfAbsent(relation.getTargetId(), k -> new ArrayList<>())
                .add(relation.getId());
            
            log.debug("Added relation: {}", relation.getDescription());
        });
    }
    
    @Override
    public Mono<Integer> addRelations(List<CodeRelation> relationList) {
        return Mono.fromCallable(() -> {
            int count = 0;
            for (CodeRelation relation : relationList) {
                relations.put(relation.getId(), relation);
                outgoingEdges.computeIfAbsent(relation.getSourceId(), k -> new ArrayList<>())
                    .add(relation.getId());
                incomingEdges.computeIfAbsent(relation.getTargetId(), k -> new ArrayList<>())
                    .add(relation.getId());
                count++;
            }
            log.info("Added {} relations to graph", count);
            return count;
        });
    }
    
    @Override
    public Mono<List<CodeRelation>> getRelationsBySource(String sourceId) {
        return Mono.fromCallable(() -> {
            List<String> relationIds = outgoingEdges.get(sourceId);
            if (relationIds == null) {
                return Collections.emptyList();
            }
            return relationIds.stream()
                .map(relations::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        });
    }
    
    @Override
    public Mono<List<CodeRelation>> getRelationsByTarget(String targetId) {
        return Mono.fromCallable(() -> {
            List<String> relationIds = incomingEdges.get(targetId);
            if (relationIds == null) {
                return Collections.emptyList();
            }
            return relationIds.stream()
                .map(relations::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        });
    }
    
    @Override
    public Mono<List<CodeRelation>> getRelationsByType(RelationType type) {
        return Mono.fromCallable(() ->
            relations.values().stream()
                .filter(relation -> relation.getType() == type)
                .collect(Collectors.toList())
        );
    }
    
    @Override
    public Mono<Void> deleteRelation(String relationId) {
        return Mono.fromRunnable(() -> {
            CodeRelation relation = relations.remove(relationId);
            if (relation != null) {
                // 从邻接表中删除
                List<String> outEdges = outgoingEdges.get(relation.getSourceId());
                if (outEdges != null) {
                    outEdges.remove(relationId);
                }
                List<String> inEdges = incomingEdges.get(relation.getTargetId());
                if (inEdges != null) {
                    inEdges.remove(relationId);
                }
            }
        });
    }
    
    @Override
    public Mono<Integer> deleteRelationsByEntity(String entityId) {
        return Mono.fromCallable(() -> {
            int count = 0;
            
            // 删除出边
            List<String> outEdges = outgoingEdges.remove(entityId);
            if (outEdges != null) {
                for (String relationId : outEdges) {
                    if (relations.remove(relationId) != null) {
                        count++;
                    }
                }
            }
            
            // 删除入边
            List<String> inEdges = incomingEdges.remove(entityId);
            if (inEdges != null) {
                for (String relationId : inEdges) {
                    if (relations.remove(relationId) != null) {
                        count++;
                    }
                }
            }
            
            return count;
        });
    }
    
    // ==================== 图查询 ====================
    
    @Override
    public Mono<List<CodeEntity>> findPath(String fromId, String toId, int maxHops) {
        return Mono.fromCallable(() -> {
            // BFS 查找最短路径
            Queue<PathNode> queue = new LinkedList<>();
            Set<String> visited = new HashSet<>();
            
            queue.offer(new PathNode(fromId, Collections.singletonList(fromId), 0));
            visited.add(fromId);
            
            while (!queue.isEmpty()) {
                PathNode current = queue.poll();
                
                if (current.entityId.equals(toId)) {
                    // 找到路径,转换为实体列表
                    return current.path.stream()
                        .map(entities::get)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                }
                
                if (current.depth >= maxHops) {
                    continue;
                }
                
                // 遍历邻居
                List<String> neighbors = getNeighborIds(current.entityId);
                for (String neighborId : neighbors) {
                    if (!visited.contains(neighborId)) {
                        visited.add(neighborId);
                        List<String> newPath = new ArrayList<>(current.path);
                        newPath.add(neighborId);
                        queue.offer(new PathNode(neighborId, newPath, current.depth + 1));
                    }
                }
            }
            
            return Collections.emptyList(); // 未找到路径
        });
    }
    
    @Override
    public Mono<List<CodeEntity>> getNeighbors(String entityId, RelationType relationType, boolean outgoing) {
        return Mono.fromCallable(() -> {
            List<String> relationIds = outgoing ? 
                outgoingEdges.get(entityId) : 
                incomingEdges.get(entityId);
            
            if (relationIds == null) {
                return Collections.emptyList();
            }
            
            return relationIds.stream()
                .map(relations::get)
                .filter(Objects::nonNull)
                .filter(rel -> relationType == null || rel.getType() == relationType)
                .map(rel -> outgoing ? rel.getTargetId() : rel.getSourceId())
                .map(entities::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        });
    }
    
    @Override
    public Mono<List<CodeEntity>> bfs(String startId, Predicate<CodeEntity> filter, int maxDepth) {
        return Mono.fromCallable(() -> {
            List<CodeEntity> result = new ArrayList<>();
            Queue<DepthNode> queue = new LinkedList<>();
            Set<String> visited = new HashSet<>();
            
            queue.offer(new DepthNode(startId, 0));
            visited.add(startId);
            
            while (!queue.isEmpty()) {
                DepthNode current = queue.poll();
                CodeEntity entity = entities.get(current.entityId);
                
                if (entity != null && filter.test(entity)) {
                    result.add(entity);
                }
                
                if (current.depth >= maxDepth) {
                    continue;
                }
                
                List<String> neighbors = getNeighborIds(current.entityId);
                for (String neighborId : neighbors) {
                    if (!visited.contains(neighborId)) {
                        visited.add(neighborId);
                        queue.offer(new DepthNode(neighborId, current.depth + 1));
                    }
                }
            }
            
            return result;
        });
    }
    
    @Override
    public Mono<List<CodeEntity>> dfs(String startId, Predicate<CodeEntity> filter, int maxDepth) {
        return Mono.fromCallable(() -> {
            List<CodeEntity> result = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            dfsRecursive(startId, 0, maxDepth, filter, visited, result);
            return result;
        });
    }
    
    private void dfsRecursive(String entityId, int depth, int maxDepth, 
                              Predicate<CodeEntity> filter, Set<String> visited, List<CodeEntity> result) {
        if (visited.contains(entityId) || depth > maxDepth) {
            return;
        }
        
        visited.add(entityId);
        CodeEntity entity = entities.get(entityId);
        
        if (entity != null && filter.test(entity)) {
            result.add(entity);
        }
        
        List<String> neighbors = getNeighborIds(entityId);
        for (String neighborId : neighbors) {
            dfsRecursive(neighborId, depth + 1, maxDepth, filter, visited, result);
        }
    }
    
    /**
     * 获取所有邻居ID (出边 + 入边)
     */
    private List<String> getNeighborIds(String entityId) {
        List<String> neighbors = new ArrayList<>();
        
        List<String> outEdges = outgoingEdges.get(entityId);
        if (outEdges != null) {
            for (String relationId : outEdges) {
                CodeRelation relation = relations.get(relationId);
                if (relation != null) {
                    neighbors.add(relation.getTargetId());
                }
            }
        }
        
        List<String> inEdges = incomingEdges.get(entityId);
        if (inEdges != null) {
            for (String relationId : inEdges) {
                CodeRelation relation = relations.get(relationId);
                if (relation != null) {
                    neighbors.add(relation.getSourceId());
                }
            }
        }
        
        return neighbors;
    }
    
    // ==================== 统计查询 ====================
    
    @Override
    public Mono<GraphStats> getStats() {
        return Mono.fromCallable(() -> {
            Map<EntityType, Integer> entitiesByType = new HashMap<>();
            for (CodeEntity entity : entities.values()) {
                entitiesByType.merge(entity.getType(), 1, Integer::sum);
            }
            
            Map<RelationType, Integer> relationsByType = new HashMap<>();
            for (CodeRelation relation : relations.values()) {
                relationsByType.merge(relation.getType(), 1, Integer::sum);
            }
            
            return GraphStats.builder()
                .totalEntities(entities.size())
                .totalRelations(relations.size())
                .entitiesByType(entitiesByType)
                .relationsByType(relationsByType)
                .lastUpdated(System.currentTimeMillis())
                .build();
        });
    }
    
    @Override
    public Mono<Integer> countEntities(EntityType type) {
        return Mono.fromCallable(() -> 
            (int) entities.values().stream()
                .filter(entity -> entity.getType() == type)
                .count()
        );
    }
    
    @Override
    public Mono<Integer> countRelations(RelationType type) {
        return Mono.fromCallable(() ->
            (int) relations.values().stream()
                .filter(relation -> relation.getType() == type)
                .count()
        );
    }
    
    @Override
    public Mono<Void> clear() {
        return Mono.fromRunnable(() -> {
            int entityCount = entities.size();
            int relationCount = relations.size();
            
            entities.clear();
            relations.clear();
            outgoingEdges.clear();
            incomingEdges.clear();
            fileIndex.clear();
            
            log.info("Cleared graph: {} entities, {} relations", entityCount, relationCount);
        });
    }
    
    // ==================== 持久化操作 ====================
    
    @Override
    public Mono<Boolean> save() {
        if (graphPath == null) {
            log.warn("Graph path not set, cannot save");
            return Mono.just(false);
        }
        
        if (objectMapper == null) {
            log.error("ObjectMapper not available, cannot save");
            return Mono.just(false);
        }
        
        return Mono.fromCallable(() -> {
            try {
                // 创建目录
                Files.createDirectories(graphPath);
                
                // 保存实体 (JSONL 格式)
                Path entitiesFile = graphPath.resolve("entities.jsonl");
                try (BufferedWriter writer = Files.newBufferedWriter(entitiesFile,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    for (CodeEntity entity : entities.values()) {
                        String json = objectMapper.writeValueAsString(entity);
                        writer.write(json);
                        writer.newLine();
                    }
                }
                
                // 保存关系 (JSONL 格式)
                Path relationsFile = graphPath.resolve("relations.jsonl");
                try (BufferedWriter writer = Files.newBufferedWriter(relationsFile,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    for (CodeRelation relation : relations.values()) {
                        String json = objectMapper.writeValueAsString(relation);
                        writer.write(json);
                        writer.newLine();
                    }
                }
                
                // 保存索引信息
                GraphMetadata metadata = new GraphMetadata();
                metadata.entityCount = entities.size();
                metadata.relationCount = relations.size();
                metadata.lastUpdated = System.currentTimeMillis();
                
                Path metadataFile = graphPath.resolve("metadata.json");
                objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(metadataFile.toFile(), metadata);
                
                log.info("Saved code graph: {} entities, {} relations to {}",
                        entities.size(), relations.size(), graphPath);
                
                return true;
            } catch (IOException e) {
                log.error("Failed to save code graph", e);
                return false;
            }
        });
    }
    
    @Override
    public Mono<Boolean> load(Path graphPath) {
        this.graphPath = graphPath;
        
        if (objectMapper == null) {
            log.error("ObjectMapper not available, cannot load");
            return Mono.just(false);
        }
        
        return Mono.fromCallable(() -> {
            if (!Files.exists(graphPath)) {
                log.warn("Graph path does not exist: {}", graphPath);
                return false;
            }
            
            Path entitiesFile = graphPath.resolve("entities.jsonl");
            Path relationsFile = graphPath.resolve("relations.jsonl");
            
            if (!Files.exists(entitiesFile) || !Files.exists(relationsFile)) {
                log.warn("Graph files not found in: {}", graphPath);
                return false;
            }
            
            try {
                // 加载实体
                Map<String, CodeEntity> loadedEntities = new HashMap<>();
                try (BufferedReader reader = Files.newBufferedReader(entitiesFile)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        CodeEntity entity = objectMapper.readValue(line, CodeEntity.class);
                        loadedEntities.put(entity.getId(), entity);
                    }
                }
                
                // 加载关系
                Map<String, CodeRelation> loadedRelations = new HashMap<>();
                try (BufferedReader reader = Files.newBufferedReader(relationsFile)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        CodeRelation relation = objectMapper.readValue(line, CodeRelation.class);
                        loadedRelations.put(relation.getId(), relation);
                    }
                }
                
                // 更新到内存
                entities.clear();
                entities.putAll(loadedEntities);
                
                relations.clear();
                relations.putAll(loadedRelations);
                
                // 重建索引
                rebuildIndices();
                
                log.info("Loaded code graph: {} entities, {} relations from {}",
                        entities.size(), relations.size(), graphPath);
                
                return true;
            } catch (IOException e) {
                log.error("Failed to load code graph", e);
                return false;
            }
        });
    }
    
    /**
     * 重建索引（邻接表和文件索引）
     */
    private void rebuildIndices() {
        outgoingEdges.clear();
        incomingEdges.clear();
        fileIndex.clear();
        
        // 重建文件索引
        for (CodeEntity entity : entities.values()) {
            fileIndex.computeIfAbsent(entity.getFilePath(), k -> new ArrayList<>())
                .add(entity.getId());
        }
        
        // 重建邻接表
        for (CodeRelation relation : relations.values()) {
            outgoingEdges.computeIfAbsent(relation.getSourceId(), k -> new ArrayList<>())
                .add(relation.getId());
            incomingEdges.computeIfAbsent(relation.getTargetId(), k -> new ArrayList<>())
                .add(relation.getId());
        }
        
        log.debug("Rebuilt indices: {} file entries, {} outgoing edges, {} incoming edges",
                fileIndex.size(), outgoingEdges.size(), incomingEdges.size());
    }
    
    // ==================== 辅助类 ====================
    
    /**
     * 图元数据
     */
    @lombok.Data
    private static class GraphMetadata {
        private int entityCount;
        private int relationCount;
        private long lastUpdated;
    }
    
    private static class PathNode {
        String entityId;
        List<String> path;
        int depth;
        
        PathNode(String entityId, List<String> path, int depth) {
            this.entityId = entityId;
            this.path = path;
            this.depth = depth;
        }
    }
    
    private static class DepthNode {
        String entityId;
        int depth;
        
        DepthNode(String entityId, int depth) {
            this.entityId = entityId;
            this.depth = depth;
        }
    }
}
