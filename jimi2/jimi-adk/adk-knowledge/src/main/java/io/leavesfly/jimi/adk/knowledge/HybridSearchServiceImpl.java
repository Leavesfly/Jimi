package io.leavesfly.jimi.adk.knowledge;

import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.knowledge.query.GraphQuery;
import io.leavesfly.jimi.adk.api.knowledge.query.HybridQuery;
import io.leavesfly.jimi.adk.api.knowledge.query.RetrievalQuery;
import io.leavesfly.jimi.adk.api.knowledge.result.GraphResult;
import io.leavesfly.jimi.adk.api.knowledge.result.HybridResult;
import io.leavesfly.jimi.adk.api.knowledge.result.RetrievalResult;
import io.leavesfly.jimi.adk.api.knowledge.spi.GraphService;
import io.leavesfly.jimi.adk.api.knowledge.spi.HybridSearchService;
import io.leavesfly.jimi.adk.api.knowledge.spi.RagService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索服务实现
 * 
 * <p>融合 Graph（结构化搜索）和 RAG（语义搜索）的结果，
 * 支持多种融合策略：RRF、加权平均、级联过滤等。
 */
@Slf4j
public class HybridSearchServiceImpl implements HybridSearchService {
    
    private final GraphService graphService;
    private final RagService ragService;
    
    private volatile boolean initialized = false;
    
    public HybridSearchServiceImpl(GraphService graphService, RagService ragService) {
        this.graphService = graphService;
        this.ragService = ragService;
    }
    
    @Override
    public Mono<Boolean> initialize(Runtime runtime) {
        return Mono.fromRunnable(() -> {
            initialized = true;
            log.info("HybridSearchService 初始化完成");
        }).thenReturn(true);
    }
    
    @Override
    public Mono<HybridResult> search(HybridQuery query) {
        if (!isEnabled()) {
            return Mono.just(HybridResult.error("混合搜索功能未启用"));
        }
        
        if (query.getKeyword() == null || query.getKeyword().trim().isEmpty()) {
            return Mono.just(HybridResult.error("搜索关键词不能为空"));
        }
        
        long startTime = System.currentTimeMillis();
        
        // 并行执行 Graph 和 Retrieval 搜索
        Mono<GraphResult> graphMono = query.isIncludeStructured() && graphService != null
                ? executeGraphSearch(query)
                : Mono.just(GraphResult.builder().success(true).build());
        
        Mono<RetrievalResult> retrievalMono = query.isIncludeSemantic() && ragService != null
                ? executeRetrievalSearch(query)
                : Mono.just(RetrievalResult.builder().success(true).build());
        
        return Mono.zip(graphMono, retrievalMono)
                .map(tuple -> {
                    GraphResult graphResult = tuple.getT1();
                    RetrievalResult retrievalResult = tuple.getT2();
                    
                    long graphElapsed = System.currentTimeMillis() - startTime;
                    
                    // 融合结果
                    List<HybridResult.HybridItem> mergedResults = fuseResults(
                            graphResult, retrievalResult, query);
                    
                    long totalElapsed = System.currentTimeMillis() - startTime;
                    
                    HybridResult result = HybridResult.builder()
                            .success(true)
                            .keyword(query.getKeyword())
                            .fusionStrategy(query.getStrategy().name())
                            .structuredEntities(graphResult.getEntities())
                            .semanticChunks(retrievalResult.getChunks())
                            .mergedResults(mergedResults)
                            .graphElapsedMs(graphElapsed)
                            .totalElapsedMs(totalElapsed)
                            .build();
                    
                    log.info("混合搜索完成: keyword={}, 结构化={}, 语义={}, 融合={}, 耗时={}ms",
                            query.getKeyword(),
                            graphResult.getEntities().size(),
                            retrievalResult.getChunks().size(),
                            mergedResults.size(),
                            totalElapsed);
                    
                    return result;
                })
                .onErrorResume(e -> {
                    log.error("混合搜索失败", e);
                    return Mono.just(HybridResult.error(e.getMessage()));
                });
    }
    
    @Override
    public Mono<HybridResult> searchGraphOnly(HybridQuery query) {
        if (graphService == null) {
            return Mono.just(HybridResult.error("Graph 服务未启用"));
        }
        
        return executeGraphSearch(query)
                .map(graphResult -> HybridResult.builder()
                        .success(true)
                        .keyword(query.getKeyword())
                        .fusionStrategy("GRAPH_ONLY")
                        .structuredEntities(graphResult.getEntities())
                        .build())
                .onErrorResume(e -> Mono.just(HybridResult.error(e.getMessage())));
    }
    
    @Override
    public Mono<HybridResult> searchRetrievalOnly(HybridQuery query) {
        if (ragService == null) {
            return Mono.just(HybridResult.error("Retrieval 服务未启用"));
        }
        
        return executeRetrievalSearch(query)
                .map(retrievalResult -> HybridResult.builder()
                        .success(true)
                        .keyword(query.getKeyword())
                        .fusionStrategy("RETRIEVAL_ONLY")
                        .semanticChunks(retrievalResult.getChunks())
                        .build())
                .onErrorResume(e -> Mono.just(HybridResult.error(e.getMessage())));
    }
    
    @Override
    public boolean isEnabled() {
        return initialized && (graphService != null || ragService != null);
    }
    
    // ==================== 搜索执行 ====================
    
    private Mono<GraphResult> executeGraphSearch(HybridQuery query) {
        GraphQuery graphQuery = GraphQuery.builder()
                .type(GraphQuery.QueryType.SEARCH_BY_SYMBOL)
                .keyword(query.getKeyword())
                .limit(query.getTopK())
                .filter(query.getGraphFilter())
                .build();
        
        return graphService.query(graphQuery)
                .onErrorResume(e -> {
                    log.warn("Graph 搜索失败: {}", e.getMessage());
                    return Mono.just(GraphResult.builder().success(true).build());
                });
    }
    
    private Mono<RetrievalResult> executeRetrievalSearch(HybridQuery query) {
        RetrievalQuery retrievalQuery = RetrievalQuery.builder()
                .query(query.getKeyword())
                .topK(query.getTopK())
                .filter(query.getRetrievalFilter())
                .build();
        
        return ragService.retrieve(retrievalQuery)
                .onErrorResume(e -> {
                    log.warn("Retrieval 搜索失败: {}", e.getMessage());
                    return Mono.just(RetrievalResult.builder().success(true).build());
                });
    }
    
    // ==================== 结果融合 ====================
    
    private List<HybridResult.HybridItem> fuseResults(
            GraphResult graphResult, RetrievalResult retrievalResult, HybridQuery query) {
        
        return switch (query.getStrategy()) {
            case RRF -> fuseByRRF(graphResult, retrievalResult);
            case WEIGHTED_AVERAGE -> fuseByWeightedAverage(graphResult, retrievalResult, query);
            case INTERSECTION -> fuseByIntersection(graphResult, retrievalResult);
            case UNION -> fuseByUnion(graphResult, retrievalResult);
            default -> fuseByRRF(graphResult, retrievalResult);
        };
    }
    
    /**
     * Reciprocal Rank Fusion (RRF)
     * score = sum(1 / (k + rank_i)) for each result list
     */
    private List<HybridResult.HybridItem> fuseByRRF(GraphResult graphResult, RetrievalResult retrievalResult) {
        int k = 60; // RRF 常数
        Map<String, HybridResult.HybridItem> itemMap = new LinkedHashMap<>();
        Map<String, Double> scoreMap = new HashMap<>();
        
        // Graph 结果
        List<GraphResult.GraphEntity> entities = graphResult.getEntities();
        for (int i = 0; i < entities.size(); i++) {
            GraphResult.GraphEntity entity = entities.get(i);
            String key = entity.getQualifiedName() != null ? entity.getQualifiedName() : entity.getId();
            double rrfScore = 1.0 / (k + i + 1);
            scoreMap.merge(key, rrfScore, Double::sum);
            
            itemMap.putIfAbsent(key, HybridResult.HybridItem.builder()
                    .source("GRAPH")
                    .id(entity.getId())
                    .name(entity.getName())
                    .content(entity.getQualifiedName())
                    .filePath(entity.getFilePath())
                    .startLine(entity.getStartLine() != null ? entity.getStartLine() : 0)
                    .endLine(entity.getEndLine() != null ? entity.getEndLine() : 0)
                    .graphRank(i + 1)
                    .retrievalRank(-1)
                    .build());
        }
        
        // Retrieval 结果
        List<RetrievalResult.CodeChunkResult> chunks = retrievalResult.getChunks();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievalResult.CodeChunkResult chunk = chunks.get(i);
            String key = chunk.getFilePath() + ":" + chunk.getStartLine();
            double rrfScore = 1.0 / (k + i + 1);
            scoreMap.merge(key, rrfScore, Double::sum);
            
            if (!itemMap.containsKey(key)) {
                itemMap.put(key, HybridResult.HybridItem.builder()
                        .source("RETRIEVAL")
                        .id(chunk.getId())
                        .name(chunk.getSymbol())
                        .content(chunk.getContent())
                        .filePath(chunk.getFilePath())
                        .startLine(chunk.getStartLine())
                        .endLine(chunk.getEndLine())
                        .graphRank(-1)
                        .retrievalRank(i + 1)
                        .build());
            } else {
                HybridResult.HybridItem existing = itemMap.get(key);
                existing.setSource("BOTH");
                existing.setRetrievalRank(i + 1);
            }
        }
        
        // 按 RRF 分数排序
        return itemMap.entrySet().stream()
                .sorted((a, b) -> Double.compare(
                        scoreMap.getOrDefault(b.getKey(), 0.0),
                        scoreMap.getOrDefault(a.getKey(), 0.0)))
                .map(entry -> {
                    HybridResult.HybridItem item = entry.getValue();
                    item.setScore(scoreMap.getOrDefault(entry.getKey(), 0.0));
                    return item;
                })
                .collect(Collectors.toList());
    }
    
    /**
     * 加权平均融合
     */
    private List<HybridResult.HybridItem> fuseByWeightedAverage(
            GraphResult graphResult, RetrievalResult retrievalResult, HybridQuery query) {
        // 简化实现：使用 RRF 但调整权重
        List<HybridResult.HybridItem> items = fuseByRRF(graphResult, retrievalResult);
        
        double graphWeight = query.getGraphWeight();
        double retrievalWeight = query.getRetrievalWeight();
        
        // 根据来源调整分数
        for (HybridResult.HybridItem item : items) {
            double adjustedScore = item.getScore();
            if ("GRAPH".equals(item.getSource())) {
                adjustedScore *= graphWeight;
            } else if ("RETRIEVAL".equals(item.getSource())) {
                adjustedScore *= retrievalWeight;
            } else {
                // BOTH - 保持原有分数（已经是两边的融合）
                adjustedScore *= (graphWeight + retrievalWeight) / 2;
            }
            item.setScore(adjustedScore);
        }
        
        // 重新排序
        items.sort(Comparator.comparingDouble(HybridResult.HybridItem::getScore).reversed());
        return items;
    }
    
    /**
     * 交集融合（两边都命中）
     */
    private List<HybridResult.HybridItem> fuseByIntersection(
            GraphResult graphResult, RetrievalResult retrievalResult) {
        List<HybridResult.HybridItem> all = fuseByRRF(graphResult, retrievalResult);
        return all.stream()
                .filter(item -> "BOTH".equals(item.getSource()))
                .collect(Collectors.toList());
    }
    
    /**
     * 并集融合（任一命中）
     */
    private List<HybridResult.HybridItem> fuseByUnion(
            GraphResult graphResult, RetrievalResult retrievalResult) {
        return fuseByRRF(graphResult, retrievalResult);
    }
}
