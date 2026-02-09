package io.leavesfly.jimi.adk.knowledge.graph.store;

import io.leavesfly.jimi.adk.knowledge.graph.model.CodeEntity;
import io.leavesfly.jimi.adk.knowledge.graph.model.CodeRelation;
import io.leavesfly.jimi.adk.knowledge.graph.model.EntityType;
import io.leavesfly.jimi.adk.knowledge.graph.model.RelationType;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 代码图存储接口
 * <p>
 * 定义代码实体和关系的存储、查询操作
 */
public interface CodeGraphStore {
    
    // ==================== 实体操作 ====================
    Mono<Void> addEntity(CodeEntity entity);
    Mono<Integer> addEntities(List<CodeEntity> entities);
    Mono<CodeEntity> getEntity(String id);
    Mono<List<CodeEntity>> getEntitiesByType(EntityType type);
    Mono<List<CodeEntity>> getEntitiesByFile(String filePath);
    Mono<Void> deleteEntity(String id);
    Mono<Integer> deleteEntitiesByFile(String filePath);
    
    // ==================== 关系操作 ====================
    Mono<Void> addRelation(CodeRelation relation);
    Mono<Integer> addRelations(List<CodeRelation> relations);
    Mono<List<CodeRelation>> getRelationsBySource(String sourceId);
    Mono<List<CodeRelation>> getRelationsByTarget(String targetId);
    Mono<List<CodeRelation>> getRelationsByType(RelationType type);
    Mono<Void> deleteRelation(String relationId);
    Mono<Integer> deleteRelationsByEntity(String entityId);
    
    // ==================== 图查询 ====================
    Mono<List<CodeEntity>> findPath(String fromId, String toId, int maxHops);
    Mono<List<CodeEntity>> getNeighbors(String entityId, RelationType relationType, boolean outgoing);
    Mono<List<CodeEntity>> bfs(String startId, Predicate<CodeEntity> filter, int maxDepth);
    Mono<List<CodeEntity>> dfs(String startId, Predicate<CodeEntity> filter, int maxDepth);
    
    // ==================== 统计查询 ====================
    Mono<GraphStats> getStats();
    Mono<Integer> countEntities(EntityType type);
    Mono<Integer> countRelations(RelationType type);
    Mono<Void> clear();
    
    // ==================== 持久化操作 ====================
    Mono<Boolean> save();
    Mono<Boolean> load(java.nio.file.Path graphPath);
    
    /**
     * 图统计信息
     */
    @lombok.Data
    @lombok.Builder
    class GraphStats {
        private Integer totalEntities;
        private Integer totalRelations;
        private Map<EntityType, Integer> entitiesByType;
        private Map<RelationType, Integer> relationsByType;
        private Long lastUpdated;
    }
}
