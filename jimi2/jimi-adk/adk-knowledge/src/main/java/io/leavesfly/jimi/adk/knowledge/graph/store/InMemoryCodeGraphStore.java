package io.leavesfly.jimi.adk.knowledge.graph.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.adk.knowledge.graph.model.CodeEntity;
import io.leavesfly.jimi.adk.knowledge.graph.model.CodeRelation;
import io.leavesfly.jimi.adk.knowledge.graph.model.EntityType;
import io.leavesfly.jimi.adk.knowledge.graph.model.RelationType;
import lombok.extern.slf4j.Slf4j;
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
 * 使用 HashMap 和邻接表存储图结构，适合中小型项目 (< 50K 实体)
 */
@Slf4j
public class InMemoryCodeGraphStore implements CodeGraphStore {
    
    private final ObjectMapper objectMapper;
    private Path graphPath;
    
    private final Map<String, CodeEntity> entities = new ConcurrentHashMap<>();
    private final Map<String, CodeRelation> relations = new ConcurrentHashMap<>();
    private final Map<String, List<String>> outgoingEdges = new ConcurrentHashMap<>();
    private final Map<String, List<String>> incomingEdges = new ConcurrentHashMap<>();
    private final Map<String, List<String>> fileIndex = new ConcurrentHashMap<>();
    
    public InMemoryCodeGraphStore() {
        this.objectMapper = new ObjectMapper();
    }
    
    public InMemoryCodeGraphStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }
    
    @Override
    public Mono<Void> addEntity(CodeEntity entity) {
        return Mono.fromRunnable(() -> {
            entities.put(entity.getId(), entity);
            fileIndex.computeIfAbsent(entity.getFilePath(), k -> new ArrayList<>()).add(entity.getId());
            log.debug("Added entity: {}", entity.getDescription());
        });
    }
    
    @Override
    public Mono<Integer> addEntities(List<CodeEntity> entityList) {
        return Mono.fromCallable(() -> {
            int count = 0;
            for (CodeEntity entity : entityList) {
                entities.put(entity.getId(), entity);
                fileIndex.computeIfAbsent(entity.getFilePath(), k -> new ArrayList<>()).add(entity.getId());
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
        return Mono.fromCallable(() -> entities.values().stream()
                .filter(entity -> entity.getType() == type).collect(Collectors.toList()));
    }
    
    @Override
    public Mono<List<CodeEntity>> getEntitiesByFile(String filePath) {
        return Mono.fromCallable(() -> {
            List<String> entityIds = fileIndex.get(filePath);
            if (entityIds == null) return Collections.emptyList();
            return entityIds.stream().map(entities::get).filter(Objects::nonNull).collect(Collectors.toList());
        });
    }
    
    @Override
    public Mono<Void> deleteEntity(String id) {
        return Mono.fromRunnable(() -> {
            CodeEntity entity = entities.remove(id);
            if (entity != null) {
                List<String> fileEntities = fileIndex.get(entity.getFilePath());
                if (fileEntities != null) fileEntities.remove(id);
            }
        });
    }
    
    @Override
    public Mono<Integer> deleteEntitiesByFile(String filePath) {
        return Mono.fromCallable(() -> {
            List<String> entityIds = fileIndex.remove(filePath);
            if (entityIds == null) return 0;
            int count = 0;
            for (String entityId : entityIds) {
                if (entities.remove(entityId) != null) count++;
            }
            return count;
        });
    }
    
    @Override
    public Mono<Void> addRelation(CodeRelation relation) {
        return Mono.fromRunnable(() -> {
            relations.put(relation.getId(), relation);
            outgoingEdges.computeIfAbsent(relation.getSourceId(), k -> new ArrayList<>()).add(relation.getId());
            incomingEdges.computeIfAbsent(relation.getTargetId(), k -> new ArrayList<>()).add(relation.getId());
        });
    }
    
    @Override
    public Mono<Integer> addRelations(List<CodeRelation> relationList) {
        return Mono.fromCallable(() -> {
            int count = 0;
            for (CodeRelation relation : relationList) {
                relations.put(relation.getId(), relation);
                outgoingEdges.computeIfAbsent(relation.getSourceId(), k -> new ArrayList<>()).add(relation.getId());
                incomingEdges.computeIfAbsent(relation.getTargetId(), k -> new ArrayList<>()).add(relation.getId());
                count++;
            }
            return count;
        });
    }
    
    @Override
    public Mono<List<CodeRelation>> getRelationsBySource(String sourceId) {
        return Mono.fromCallable(() -> {
            List<String> relationIds = outgoingEdges.get(sourceId);
            if (relationIds == null) return Collections.emptyList();
            return relationIds.stream().map(relations::get).filter(Objects::nonNull).collect(Collectors.toList());
        });
    }
    
    @Override
    public Mono<List<CodeRelation>> getRelationsByTarget(String targetId) {
        return Mono.fromCallable(() -> {
            List<String> relationIds = incomingEdges.get(targetId);
            if (relationIds == null) return Collections.emptyList();
            return relationIds.stream().map(relations::get).filter(Objects::nonNull).collect(Collectors.toList());
        });
    }
    
    @Override
    public Mono<List<CodeRelation>> getRelationsByType(RelationType type) {
        return Mono.fromCallable(() -> relations.values().stream()
                .filter(r -> r.getType() == type).collect(Collectors.toList()));
    }
    
    @Override
    public Mono<Void> deleteRelation(String relationId) {
        return Mono.fromRunnable(() -> {
            CodeRelation relation = relations.remove(relationId);
            if (relation != null) {
                List<String> outEdges = outgoingEdges.get(relation.getSourceId());
                if (outEdges != null) outEdges.remove(relationId);
                List<String> inEdges = incomingEdges.get(relation.getTargetId());
                if (inEdges != null) inEdges.remove(relationId);
            }
        });
    }
    
    @Override
    public Mono<Integer> deleteRelationsByEntity(String entityId) {
        return Mono.fromCallable(() -> {
            int count = 0;
            List<String> outEdges = outgoingEdges.remove(entityId);
            if (outEdges != null) {
                for (String rid : outEdges) { if (relations.remove(rid) != null) count++; }
            }
            List<String> inEdges = incomingEdges.remove(entityId);
            if (inEdges != null) {
                for (String rid : inEdges) { if (relations.remove(rid) != null) count++; }
            }
            return count;
        });
    }
    
    @Override
    public Mono<List<CodeEntity>> findPath(String fromId, String toId, int maxHops) {
        return Mono.fromCallable(() -> {
            Queue<PathNode> queue = new LinkedList<>();
            Set<String> visited = new HashSet<>();
            queue.offer(new PathNode(fromId, Collections.singletonList(fromId), 0));
            visited.add(fromId);
            while (!queue.isEmpty()) {
                PathNode current = queue.poll();
                if (current.entityId.equals(toId)) {
                    return current.path.stream().map(entities::get).filter(Objects::nonNull).collect(Collectors.toList());
                }
                if (current.depth >= maxHops) continue;
                for (String neighborId : getNeighborIds(current.entityId)) {
                    if (!visited.contains(neighborId)) {
                        visited.add(neighborId);
                        List<String> newPath = new ArrayList<>(current.path);
                        newPath.add(neighborId);
                        queue.offer(new PathNode(neighborId, newPath, current.depth + 1));
                    }
                }
            }
            return Collections.emptyList();
        });
    }
    
    @Override
    public Mono<List<CodeEntity>> getNeighbors(String entityId, RelationType relationType, boolean outgoing) {
        return Mono.fromCallable(() -> {
            List<String> relationIds = outgoing ? outgoingEdges.get(entityId) : incomingEdges.get(entityId);
            if (relationIds == null) return Collections.emptyList();
            return relationIds.stream()
                    .map(relations::get).filter(Objects::nonNull)
                    .filter(rel -> relationType == null || rel.getType() == relationType)
                    .map(rel -> outgoing ? rel.getTargetId() : rel.getSourceId())
                    .map(entities::get).filter(Objects::nonNull)
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
                if (entity != null && filter.test(entity)) result.add(entity);
                if (current.depth >= maxDepth) continue;
                for (String neighborId : getNeighborIds(current.entityId)) {
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
        if (visited.contains(entityId) || depth > maxDepth) return;
        visited.add(entityId);
        CodeEntity entity = entities.get(entityId);
        if (entity != null && filter.test(entity)) result.add(entity);
        for (String neighborId : getNeighborIds(entityId)) {
            dfsRecursive(neighborId, depth + 1, maxDepth, filter, visited, result);
        }
    }
    
    private List<String> getNeighborIds(String entityId) {
        List<String> neighbors = new ArrayList<>();
        List<String> outEdges = outgoingEdges.get(entityId);
        if (outEdges != null) {
            for (String rid : outEdges) {
                CodeRelation r = relations.get(rid);
                if (r != null) neighbors.add(r.getTargetId());
            }
        }
        List<String> inEdges = incomingEdges.get(entityId);
        if (inEdges != null) {
            for (String rid : inEdges) {
                CodeRelation r = relations.get(rid);
                if (r != null) neighbors.add(r.getSourceId());
            }
        }
        return neighbors;
    }
    
    @Override
    public Mono<GraphStats> getStats() {
        return Mono.fromCallable(() -> {
            Map<EntityType, Integer> entitiesByType = new HashMap<>();
            for (CodeEntity e : entities.values()) entitiesByType.merge(e.getType(), 1, Integer::sum);
            Map<RelationType, Integer> relationsByType = new HashMap<>();
            for (CodeRelation r : relations.values()) relationsByType.merge(r.getType(), 1, Integer::sum);
            return GraphStats.builder().totalEntities(entities.size()).totalRelations(relations.size())
                    .entitiesByType(entitiesByType).relationsByType(relationsByType)
                    .lastUpdated(System.currentTimeMillis()).build();
        });
    }
    
    @Override
    public Mono<Integer> countEntities(EntityType type) {
        return Mono.fromCallable(() -> (int) entities.values().stream().filter(e -> e.getType() == type).count());
    }
    
    @Override
    public Mono<Integer> countRelations(RelationType type) {
        return Mono.fromCallable(() -> (int) relations.values().stream().filter(r -> r.getType() == type).count());
    }
    
    @Override
    public Mono<Void> clear() {
        return Mono.fromRunnable(() -> {
            entities.clear(); relations.clear();
            outgoingEdges.clear(); incomingEdges.clear(); fileIndex.clear();
            log.info("Cleared graph store");
        });
    }
    
    @Override
    public Mono<Boolean> save() {
        if (graphPath == null) { log.warn("Graph path not set"); return Mono.just(false); }
        return Mono.fromCallable(() -> {
            try {
                Files.createDirectories(graphPath);
                Path entitiesFile = graphPath.resolve("entities.jsonl");
                try (BufferedWriter writer = Files.newBufferedWriter(entitiesFile,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    for (CodeEntity entity : entities.values()) {
                        writer.write(objectMapper.writeValueAsString(entity)); writer.newLine();
                    }
                }
                Path relationsFile = graphPath.resolve("relations.jsonl");
                try (BufferedWriter writer = Files.newBufferedWriter(relationsFile,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    for (CodeRelation relation : relations.values()) {
                        writer.write(objectMapper.writeValueAsString(relation)); writer.newLine();
                    }
                }
                log.info("Saved graph: {} entities, {} relations to {}", entities.size(), relations.size(), graphPath);
                return true;
            } catch (IOException e) {
                log.error("Failed to save graph", e); return false;
            }
        });
    }
    
    @Override
    public Mono<Boolean> load(Path graphPath) {
        this.graphPath = graphPath;
        return Mono.fromCallable(() -> {
            if (!Files.exists(graphPath)) { log.warn("Path not exist: {}", graphPath); return false; }
            Path entitiesFile = graphPath.resolve("entities.jsonl");
            Path relationsFile = graphPath.resolve("relations.jsonl");
            if (!Files.exists(entitiesFile) || !Files.exists(relationsFile)) return false;
            try {
                Map<String, CodeEntity> loaded = new HashMap<>();
                try (BufferedReader reader = Files.newBufferedReader(entitiesFile)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        CodeEntity e = objectMapper.readValue(line, CodeEntity.class);
                        loaded.put(e.getId(), e);
                    }
                }
                Map<String, CodeRelation> loadedRels = new HashMap<>();
                try (BufferedReader reader = Files.newBufferedReader(relationsFile)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        CodeRelation r = objectMapper.readValue(line, CodeRelation.class);
                        loadedRels.put(r.getId(), r);
                    }
                }
                entities.clear(); entities.putAll(loaded);
                relations.clear(); relations.putAll(loadedRels);
                rebuildIndices();
                log.info("Loaded graph: {} entities, {} relations", entities.size(), relations.size());
                return true;
            } catch (IOException e) {
                log.error("Failed to load graph", e); return false;
            }
        });
    }
    
    private void rebuildIndices() {
        outgoingEdges.clear(); incomingEdges.clear(); fileIndex.clear();
        for (CodeEntity e : entities.values()) {
            fileIndex.computeIfAbsent(e.getFilePath(), k -> new ArrayList<>()).add(e.getId());
        }
        for (CodeRelation r : relations.values()) {
            outgoingEdges.computeIfAbsent(r.getSourceId(), k -> new ArrayList<>()).add(r.getId());
            incomingEdges.computeIfAbsent(r.getTargetId(), k -> new ArrayList<>()).add(r.getId());
        }
    }
    
    private static class PathNode {
        String entityId; List<String> path; int depth;
        PathNode(String entityId, List<String> path, int depth) {
            this.entityId = entityId; this.path = path; this.depth = depth;
        }
    }
    
    private static class DepthNode {
        String entityId; int depth;
        DepthNode(String entityId, int depth) { this.entityId = entityId; this.depth = depth; }
    }
}
