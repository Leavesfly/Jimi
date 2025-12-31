package io.leavesfly.jimi.knowledge.graph;

import io.leavesfly.jimi.knowledge.graph.model.CodeEntity;
import io.leavesfly.jimi.knowledge.graph.model.EntityType;
import io.leavesfly.jimi.knowledge.graph.model.RelationType;
import io.leavesfly.jimi.knowledge.graph.navigator.GraphNavigator;
import io.leavesfly.jimi.knowledge.graph.navigator.ImpactAnalyzer;
import io.leavesfly.jimi.knowledge.graph.store.CodeGraphStore;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 图检索引擎
 * <p>
 * 基于代码图进行结构化检索,支持:
 * - 按实体名称/类型查询
 * - 符号查找 (类、方法、字段)
 * - 关系查询 (调用关系、继承关系等)
 * - 结构化代码定位
 */
@Slf4j
@Component
public class GraphSearchEngine {

    private final CodeGraphStore graphStore;
    private final GraphNavigator navigator;

    public GraphSearchEngine(CodeGraphStore graphStore,
                             GraphNavigator navigator) {
        this.graphStore = graphStore;
        this.navigator = navigator;
    }

    /**
     * 符号搜索: 根据符号名称查找代码实体
     *
     * @param symbolName  符号名称 (支持部分匹配)
     * @param entityTypes 实体类型过滤 (null表示所有类型)
     * @param limit       返回数量限制
     * @return 搜索结果
     */
    public Mono<GraphSearchResult> searchBySymbol(String symbolName,
                                                  Set<EntityType> entityTypes,
                                                  int limit) {
        return Mono.fromCallable(() -> {
            long startTime = System.currentTimeMillis();
            GraphSearchResult result = new GraphSearchResult();
            result.setQuery(symbolName);
            result.setSearchType(SearchType.SYMBOL);

            List<ScoredEntity> scoredEntities = new ArrayList<>();

            // 获取所有实体并进行匹配
            if (entityTypes == null || entityTypes.isEmpty()) {
                // 搜索所有类型
                for (EntityType type : EntityType.values()) {
                    List<CodeEntity> entities = graphStore.getEntitiesByType(type).block();
                    if (entities != null) {
                        scoredEntities.addAll(scoreEntities(entities, symbolName));
                    }
                }
            } else {
                // 按指定类型搜索
                for (EntityType type : entityTypes) {
                    List<CodeEntity> entities = graphStore.getEntitiesByType(type).block();
                    if (entities != null) {
                        scoredEntities.addAll(scoreEntities(entities, symbolName));
                    }
                }
            }

            // 按分数排序并取TopK
            scoredEntities.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            if (scoredEntities.size() > limit) {
                scoredEntities = scoredEntities.subList(0, limit);
            }

            result.setResults(scoredEntities);
            result.setTotalResults(scoredEntities.size());
            result.setElapsedMs(System.currentTimeMillis() - startTime);
            result.setSuccess(true);

            log.debug("Symbol search completed: {} results for '{}'",
                    scoredEntities.size(), symbolName);

            return result;
        });
    }

    /**
     * 关系查询: 查找与指定实体有特定关系的其他实体
     *
     * @param entityId      实体ID
     * @param relationTypes 关系类型
     * @param direction     查询方向 (OUTGOING/INCOMING/BOTH)
     * @param limit         返回数量限制
     * @return 搜索结果
     */
    public Mono<GraphSearchResult> searchByRelation(String entityId,
                                                    Set<RelationType> relationTypes,
                                                    GraphNavigator.Direction direction,
                                                    int limit) {
        return Mono.fromCallable(() -> {
            long startTime = System.currentTimeMillis();
            GraphSearchResult result = new GraphSearchResult();
            result.setQuery("Relation search for: " + entityId);
            result.setSearchType(SearchType.RELATION);

            CodeEntity entity = graphStore.getEntity(entityId).block();
            if (entity == null) {
                result.setSuccess(false);
                result.setErrorMessage("Entity not found: " + entityId);
                return result;
            }

            // 使用 GraphNavigator 获取邻居
            List<CodeEntity> neighbors = navigator.getNeighbors(entityId, direction, relationTypes).block();

            if (neighbors == null) {
                neighbors = new ArrayList<>();
            }

            // 转换为 ScoredEntity (关系查询默认分数相同)
            List<ScoredEntity> scoredEntities = neighbors.stream()
                    .limit(limit)
                    .map(e -> ScoredEntity.builder()
                            .entity(e)
                            .score(1.0)
                            .reason("Related via " + (relationTypes != null ? relationTypes : "any relation"))
                            .build())
                    .collect(Collectors.toList());

            result.setResults(scoredEntities);
            result.setTotalResults(scoredEntities.size());
            result.setElapsedMs(System.currentTimeMillis() - startTime);
            result.setSuccess(true);

            return result;
        });
    }

    /**
     * 文件搜索: 查找指定文件中的所有实体
     *
     * @param filePath 文件路径 (支持部分匹配)
     * @param limit    返回数量限制
     * @return 搜索结果
     */
    public Mono<GraphSearchResult> searchByFile(String filePath, int limit) {
        return Mono.fromCallable(() -> {
            long startTime = System.currentTimeMillis();
            GraphSearchResult result = new GraphSearchResult();
            result.setQuery(filePath);
            result.setSearchType(SearchType.FILE);

            // 查找所有匹配的文件
            List<ScoredEntity> scoredEntities = new ArrayList<>();

            for (EntityType type : EntityType.values()) {
                List<CodeEntity> entities = graphStore.getEntitiesByType(type).block();
                if (entities != null) {
                    for (CodeEntity entity : entities) {
                        if (entity.getFilePath() != null &&
                                entity.getFilePath().contains(filePath)) {

                            // 计算文件路径匹配分数
                            double score = calculateFilePathScore(entity.getFilePath(), filePath);

                            scoredEntities.add(ScoredEntity.builder()
                                    .entity(entity)
                                    .score(score)
                                    .reason("File path match: " + entity.getFilePath())
                                    .build());
                        }
                    }
                }
            }

            // 排序并限制数量
            scoredEntities.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            if (scoredEntities.size() > limit) {
                scoredEntities = scoredEntities.subList(0, limit);
            }

            result.setResults(scoredEntities);
            result.setTotalResults(scoredEntities.size());
            result.setElapsedMs(System.currentTimeMillis() - startTime);
            result.setSuccess(true);

            return result;
        });
    }

    /**
     * 上下文搜索: 基于代码上下文查找相关实体
     * (结合符号、调用关系、继承关系等多维度)
     *
     * @param contextQuery 上下文查询
     * @return 搜索结果
     */
    public Mono<GraphSearchResult> searchByContext(ContextQuery contextQuery) {
        return Mono.fromCallable(() -> {
            long startTime = System.currentTimeMillis();
            GraphSearchResult result = new GraphSearchResult();
            result.setQuery(contextQuery.getDescription());
            result.setSearchType(SearchType.CONTEXT);

            Set<ScoredEntity> allResults = new HashSet<>();

            // 1. 符号查询
            if (contextQuery.getSymbols() != null && !contextQuery.getSymbols().isEmpty()) {
                for (String symbol : contextQuery.getSymbols()) {
                    GraphSearchResult symbolResult = searchBySymbol(symbol,
                            contextQuery.getEntityTypes(), 50).block();

                    if (symbolResult != null && symbolResult.getSuccess()) {
                        allResults.addAll(symbolResult.getResults());
                    }
                }
            }

            // 2. 文件查询
            if (contextQuery.getFilePaths() != null && !contextQuery.getFilePaths().isEmpty()) {
                for (String filePath : contextQuery.getFilePaths()) {
                    GraphSearchResult fileResult = searchByFile(filePath, 50).block();

                    if (fileResult != null && fileResult.getSuccess()) {
                        allResults.addAll(fileResult.getResults());
                    }
                }
            }

            // 3. 关系扩展
            if (contextQuery.isIncludeRelated() && !allResults.isEmpty()) {
                Set<ScoredEntity> relatedEntities = new HashSet<>();

                for (ScoredEntity scored : allResults) {
                    GraphSearchResult relatedResult = searchByRelation(
                            scored.getEntity().getId(),
                            contextQuery.getRelationTypes(),
                            GraphNavigator.Direction.BOTH,
                            10
                    ).block();

                    if (relatedResult != null && relatedResult.getSuccess()) {
                        // 相关实体的分数打折
                        relatedResult.getResults().forEach(r -> r.setScore(r.getScore() * 0.5));
                        relatedEntities.addAll(relatedResult.getResults());
                    }
                }

                allResults.addAll(relatedEntities);
            }

            // 排序并限制数量
            List<ScoredEntity> finalResults = new ArrayList<>(allResults);
            finalResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

            if (finalResults.size() > contextQuery.getLimit()) {
                finalResults = finalResults.subList(0, contextQuery.getLimit());
            }

            result.setResults(finalResults);
            result.setTotalResults(finalResults.size());
            result.setElapsedMs(System.currentTimeMillis() - startTime);
            result.setSuccess(true);

            log.info("Context search completed: {} results in {}ms",
                    finalResults.size(), result.getElapsedMs());

            return result;
        });
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 对实体列表进行评分
     */
    private List<ScoredEntity> scoreEntities(List<CodeEntity> entities, String query) {
        List<ScoredEntity> scored = new ArrayList<>();
        String queryLower = query.toLowerCase();

        for (CodeEntity entity : entities) {
            String name = entity.getName();
            String qualifiedName = entity.getQualifiedName();

            if (name == null) {
                continue;
            }

            String nameLower = name.toLowerCase();
            String qualifiedLower = qualifiedName != null ? qualifiedName.toLowerCase() : "";

            double score = 0.0;
            String reason = "";

            // 精确匹配 (最高分)
            if (nameLower.equals(queryLower)) {
                score = 1.0;
                reason = "Exact match";
            }
            // 开头匹配
            else if (nameLower.startsWith(queryLower)) {
                score = 0.8;
                reason = "Name starts with query";
            }
            // 包含匹配
            else if (nameLower.contains(queryLower)) {
                score = 0.6;
                reason = "Name contains query";
            }
            // 限定名匹配
            else if (qualifiedLower.contains(queryLower)) {
                score = 0.4;
                reason = "Qualified name contains query";
            }
            // 驼峰匹配 (如: queryLower="gse", name="GraphSearchEngine")
            else if (matchesCamelCase(name, query)) {
                score = 0.5;
                reason = "CamelCase match";
            }

            if (score > 0) {
                scored.add(ScoredEntity.builder()
                        .entity(entity)
                        .score(score)
                        .reason(reason)
                        .build());
            }
        }

        return scored;
    }

    /**
     * 驼峰匹配算法
     */
    private boolean matchesCamelCase(String name, String query) {
        if (name == null || query == null || query.isEmpty()) {
            return false;
        }

        String capitals = name.chars()
                .filter(Character::isUpperCase)
                .collect(StringBuilder::new,
                        StringBuilder::appendCodePoint,
                        StringBuilder::append)
                .toString()
                .toLowerCase();

        return capitals.contains(query.toLowerCase());
    }

    /**
     * 计算文件路径匹配分数
     */
    private double calculateFilePathScore(String fullPath, String query) {
        if (fullPath.equals(query)) {
            return 1.0;
        }
        if (fullPath.endsWith(query)) {
            return 0.9;
        }
        if (fullPath.contains(query)) {
            // 路径越短,匹配度越高
            double ratio = (double) query.length() / fullPath.length();
            return 0.5 + (ratio * 0.3);
        }
        return 0.3;
    }

    // ==================== 数据模型 ====================

    /**
     * 搜索类型
     */
    public enum SearchType {
        SYMBOL,    // 符号搜索
        RELATION,  // 关系搜索
        FILE,      // 文件搜索
        CONTEXT    // 上下文搜索
    }

    /**
     * 评分实体
     */
    @Data
    @Builder
    public static class ScoredEntity {
        private CodeEntity entity;
        private Double score;
        private String reason;
    }

    /**
     * 图搜索结果
     */
    @Data
    public static class GraphSearchResult {
        private String query;
        private SearchType searchType;
        private List<ScoredEntity> results = new ArrayList<>();
        private Integer totalResults;
        private Long elapsedMs;
        private Boolean success;
        private String errorMessage;
    }

    /**
     * 上下文查询
     */
    @Data
    @Builder
    public static class ContextQuery {
        private String description;
        private List<String> symbols;              // 符号列表
        private List<String> filePaths;            // 文件路径列表
        private Set<EntityType> entityTypes;       // 实体类型过滤
        private Set<RelationType> relationTypes;   // 关系类型过滤
        private boolean includeRelated;            // 是否包含相关实体
        private int limit;                         // 返回数量限制
    }
}
