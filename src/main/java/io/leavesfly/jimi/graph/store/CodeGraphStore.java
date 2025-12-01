package io.leavesfly.jimi.graph.store;

import io.leavesfly.jimi.graph.model.CodeEntity;
import io.leavesfly.jimi.graph.model.CodeRelation;
import io.leavesfly.jimi.graph.model.EntityType;
import io.leavesfly.jimi.graph.model.RelationType;
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
    
    /**
     * 添加实体
     */
    Mono<Void> addEntity(CodeEntity entity);
    
    /**
     * 批量添加实体
     */
    Mono<Integer> addEntities(List<CodeEntity> entities);
    
    /**
     * 获取实体
     */
    Mono<CodeEntity> getEntity(String id);
    
    /**
     * 按类型获取实体
     */
    Mono<List<CodeEntity>> getEntitiesByType(EntityType type);
    
    /**
     * 按文件路径获取实体
     */
    Mono<List<CodeEntity>> getEntitiesByFile(String filePath);
    
    /**
     * 删除实体
     */
    Mono<Void> deleteEntity(String id);
    
    /**
     * 删除文件的所有实体
     */
    Mono<Integer> deleteEntitiesByFile(String filePath);
    
    // ==================== 关系操作 ====================
    
    /**
     * 添加关系
     */
    Mono<Void> addRelation(CodeRelation relation);
    
    /**
     * 批量添加关系
     */
    Mono<Integer> addRelations(List<CodeRelation> relations);
    
    /**
     * 获取从某个实体出发的所有关系
     */
    Mono<List<CodeRelation>> getRelationsBySource(String sourceId);
    
    /**
     * 获取指向某个实体的所有关系
     */
    Mono<List<CodeRelation>> getRelationsByTarget(String targetId);
    
    /**
     * 获取指定类型的所有关系
     */
    Mono<List<CodeRelation>> getRelationsByType(RelationType type);
    
    /**
     * 删除关系
     */
    Mono<Void> deleteRelation(String relationId);
    
    /**
     * 删除与某个实体相关的所有关系
     */
    Mono<Integer> deleteRelationsByEntity(String entityId);
    
    // ==================== 图查询 ====================
    
    /**
     * 查找两个实体之间的路径
     *
     * @param fromId 起始实体ID
     * @param toId 目标实体ID
     * @param maxHops 最大跳数
     * @return 路径上的实体列表
     */
    Mono<List<CodeEntity>> findPath(String fromId, String toId, int maxHops);
    
    /**
     * 获取邻居实体
     *
     * @param entityId 实体ID
     * @param relationType 关系类型 (null表示所有类型)
     * @param outgoing true表示出边,false表示入边
     * @return 邻居实体列表
     */
    Mono<List<CodeEntity>> getNeighbors(String entityId, RelationType relationType, boolean outgoing);
    
    /**
     * 广度优先搜索
     *
     * @param startId 起始实体ID
     * @param filter 过滤条件
     * @param maxDepth 最大深度
     * @return 搜索到的实体列表
     */
    Mono<List<CodeEntity>> bfs(String startId, Predicate<CodeEntity> filter, int maxDepth);
    
    /**
     * 深度优先搜索
     *
     * @param startId 起始实体ID
     * @param filter 过滤条件
     * @param maxDepth 最大深度
     * @return 搜索到的实体列表
     */
    Mono<List<CodeEntity>> dfs(String startId, Predicate<CodeEntity> filter, int maxDepth);
    
    // ==================== 统计查询 ====================
    
    /**
     * 获取图统计信息
     */
    Mono<GraphStats> getStats();
    
    /**
     * 统计实体数量
     */
    Mono<Integer> countEntities(EntityType type);
    
    /**
     * 统计关系数量
     */
    Mono<Integer> countRelations(RelationType type);
    
    /**
     * 清空图
     */
    Mono<Void> clear();
    
    // ==================== 持久化操作 ====================
    
    /**
     * 保存图到磁盘
     * 
     * @return 是否成功
     */
    Mono<Boolean> save();
    
    /**
     * 从磁盘加载图
     * 
     * @param graphPath 图存储路径
     * @return 是否成功
     */
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
