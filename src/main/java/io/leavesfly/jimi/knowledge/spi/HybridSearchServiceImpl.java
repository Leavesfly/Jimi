package io.leavesfly.jimi.knowledge.spi;

import io.leavesfly.jimi.core.engine.runtime.Runtime;
import io.leavesfly.jimi.knowledge.domain.query.GraphQuery;
import io.leavesfly.jimi.knowledge.domain.query.HybridQuery;
import io.leavesfly.jimi.knowledge.domain.query.RetrievalQuery;
import io.leavesfly.jimi.knowledge.domain.result.GraphResult;
import io.leavesfly.jimi.knowledge.domain.result.HybridResult;
import io.leavesfly.jimi.knowledge.domain.result.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 混合搜索服务实现
 * 
 * <p>组合 GraphService 和 RagService 的能力，
 * 实现多种融合策略（RRF、加权平均等）。
 */
@Slf4j
public class HybridSearchServiceImpl implements HybridSearchService {
    
    private static final int RRF_K = 60; // RRF 常数 k
    
    private final GraphService graphService;
    private final RagService retrievalService;
    
    public HybridSearchServiceImpl(GraphService graphService, RagService retrievalService) {
        this.graphService = graphService;
        this.retrievalService = retrievalService;
    }
    
    @Override
    public Mono<Boolean> initialize(Runtime runtime) {
        log.info("HybridSearchService 初始化完成");
        return Mono.just(true);
    }
    
    @Override
    public Mono<HybridResult> search(HybridQuery query) {
        if (!isEnabled()) {
            return Mono.just(HybridResult.error("混合搜索功能未启用"));
        }
        
        long startTime = System.currentTimeMillis();
        
        // 并行执行 Graph 和 Retrieval 搜索
        Mono<GraphSearchResult> graphMono = query.isIncludeStructured() && graphService.isEnabled()
                ? executeGraphSearch(query)
                : Mono.just(new GraphSearchResult(Collections.emptyList(), 0));
        
        Mono<RetrievalSearchResult> retrievalMono = query.isIncludeSemantic() && retrievalService.isEnabled()
                ? executeRetrievalSearch(query)
                : Mono.just(new RetrievalSearchResult(Collections.emptyList(), 0));
        
        return Mono.zip(graphMono, retrievalMono)
                .map(tuple -> {
                    GraphSearchResult graphResult = tuple.getT1();
                    RetrievalSearchResult retrievalResult = tuple.getT2();
                    
                    // 融合结果
                    List<HybridResult.HybridItem> mergedResults = fuseResults(
                            graphResult.entities,
                            retrievalResult.chunks,
                            query);
                    
                    long totalElapsed = System.currentTimeMillis() - startTime;
                    
                    return HybridResult.builder()
                            .success(true)
                            .keyword(query.getKeyword())
                            .fusionStrategy(query.getStrategy().name())
                            .structuredEntities(graphResult.entities)
                            .semanticChunks(retrievalResult.chunks)
                            .mergedResults(mergedResults)
                            .graphElapsedMs(graphResult.elapsedMs)
                            .retrievalElapsedMs(retrievalResult.elapsedMs)
                            .totalElapsedMs(totalElapsed)
                            .build();
                })
                .onErrorResume(e -> {
                    log.error("混合搜索失败", e);
                    return Mono.just(HybridResult.error(e.getMessage()));
                });
    }
    
    @Override
    public Mono<HybridResult> searchGraphOnly(HybridQuery query) {
        if (!graphService.isEnabled()) {
            return Mono.just(HybridResult.error("Graph 功能未启用"));
        }
        
        return executeGraphSearch(query)
                .map(result -> {
                    List<HybridResult.HybridItem> items = result.entities.stream()
                            .map(entity -> HybridResult.HybridItem.builder()
                                    .source("GRAPH")
                                    .id(entity.getId())
                                    .name(entity.getName())
                                    .filePath(entity.getFilePath())
                                    .startLine(entity.getStartLine())
                                    .endLine(entity.getEndLine())
                                    .score(1.0)
                                    .graphRank(result.entities.indexOf(entity) + 1)
                                    .retrievalRank(-1)
                                    .build())
                            .collect(Collectors.toList());
                    
                    return HybridResult.builder()
                            .success(true)
                            .keyword(query.getKeyword())
                            .fusionStrategy("GRAPH_ONLY")
                            .structuredEntities(result.entities)
                            .mergedResults(items)
                            .graphElapsedMs(result.elapsedMs)
                            .totalElapsedMs(result.elapsedMs)
                            .build();
                });
    }
    
    @Override
    public Mono<HybridResult> searchRetrievalOnly(HybridQuery query) {
        if (!retrievalService.isEnabled()) {
            return Mono.just(HybridResult.error("Retrieval 功能未启用"));
        }
        
        return executeRetrievalSearch(query)
                .map(result -> {
                    List<HybridResult.HybridItem> items = result.chunks.stream()
                            .map(chunk -> HybridResult.HybridItem.builder()
                                    .source("RETRIEVAL")
                                    .id(chunk.getId())
                                    .name(chunk.getSymbol())
                                    .content(chunk.getContent())
                                    .filePath(chunk.getFilePath())
                                    .startLine(chunk.getStartLine())
                                    .endLine(chunk.getEndLine())
                                    .score(chunk.getScore())
                                    .graphRank(-1)
                                    .retrievalRank(result.chunks.indexOf(chunk) + 1)
                                    .build())
                            .collect(Collectors.toList());
                    
                    return HybridResult.builder()
                            .success(true)
                            .keyword(query.getKeyword())
                            .fusionStrategy("RETRIEVAL_ONLY")
                            .semanticChunks(result.chunks)
                            .mergedResults(items)
                            .retrievalElapsedMs(result.elapsedMs)
                            .totalElapsedMs(result.elapsedMs)
                            .build();
                });
    }
    
    @Override
    public boolean isEnabled() {
        return (graphService != null && graphService.isEnabled()) 
                || (retrievalService != null && retrievalService.isEnabled());
    }
    
    // ==================== 内部方法 ====================
    
    private Mono<GraphSearchResult> executeGraphSearch(HybridQuery query) {
        long startTime = System.currentTimeMillis();
        
        GraphQuery graphQuery = GraphQuery.builder()
                .type(GraphQuery.QueryType.SEARCH_BY_SYMBOL)
                .keyword(query.getKeyword())
                .limit(query.getTopK())
                .filter(query.getGraphFilter())
                .build();
        
        return graphService.query(graphQuery)
                .map(result -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    return new GraphSearchResult(result.getEntities(), elapsed);
                })
                .onErrorResume(e -> {
                    log.warn("Graph 搜索失败: {}", e.getMessage());
                    return Mono.just(new GraphSearchResult(Collections.emptyList(), 0));
                });
    }
    
    private Mono<RetrievalSearchResult> executeRetrievalSearch(HybridQuery query) {
        long startTime = System.currentTimeMillis();
        
        RetrievalQuery retrievalQuery = RetrievalQuery.builder()
                .query(query.getKeyword())
                .topK(query.getTopK())
                .filter(query.getRetrievalFilter())
                .build();
        
        return retrievalService.retrieve(retrievalQuery)
                .map(result -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    return new RetrievalSearchResult(result.getChunks(), elapsed);
                })
                .onErrorResume(e -> {
                    log.warn("Retrieval 搜索失败: {}", e.getMessage());
                    return Mono.just(new RetrievalSearchResult(Collections.emptyList(), 0));
                });
    }
    
    private List<HybridResult.HybridItem> fuseResults(
            List<GraphResult.GraphEntity> graphEntities,
            List<RetrievalResult.CodeChunkResult> retrievalChunks,
            HybridQuery query) {
        
        switch (query.getStrategy()) {
            case RRF:
                return fuseWithRRF(graphEntities, retrievalChunks, query);
            case WEIGHTED_AVERAGE:
                return fuseWithWeightedAverage(graphEntities, retrievalChunks, query);
            case UNION:
            default:
                return fuseWithUnion(graphEntities, retrievalChunks, query);
        }
    }
    
    /**
     * RRF (Reciprocal Rank Fusion) 融合
     * score = sum(1 / (k + rank))
     */
    private List<HybridResult.HybridItem> fuseWithRRF(
            List<GraphResult.GraphEntity> graphEntities,
            List<RetrievalResult.CodeChunkResult> retrievalChunks,
            HybridQuery query) {
        
        Map<String, HybridResult.HybridItem> itemMap = new HashMap<>();
        
        // 添加 Graph 结果
        for (int i = 0; i < graphEntities.size(); i++) {
            GraphResult.GraphEntity entity = graphEntities.get(i);
            String key = entity.getFilePath() + ":" + entity.getStartLine();
            
            double rrfScore = 1.0 / (RRF_K + i + 1);
            
            HybridResult.HybridItem item = HybridResult.HybridItem.builder()
                    .source("GRAPH")
                    .id(entity.getId())
                    .name(entity.getName())
                    .filePath(entity.getFilePath())
                    .startLine(entity.getStartLine() != null ? entity.getStartLine() : 0)
                    .endLine(entity.getEndLine() != null ? entity.getEndLine() : 0)
                    .score(rrfScore)
                    .graphRank(i + 1)
                    .retrievalRank(-1)
                    .build();
            
            itemMap.put(key, item);
        }
        
        // 添加/合并 Retrieval 结果
        for (int i = 0; i < retrievalChunks.size(); i++) {
            RetrievalResult.CodeChunkResult chunk = retrievalChunks.get(i);
            String key = chunk.getFilePath() + ":" + chunk.getStartLine();
            
            double rrfScore = 1.0 / (RRF_K + i + 1);
            
            if (itemMap.containsKey(key)) {
                // 合并分数
                HybridResult.HybridItem existing = itemMap.get(key);
                HybridResult.HybridItem merged = HybridResult.HybridItem.builder()
                        .source("BOTH")
                        .id(existing.getId())
                        .name(existing.getName())
                        .content(chunk.getContent())
                        .filePath(existing.getFilePath())
                        .startLine(existing.getStartLine())
                        .endLine(existing.getEndLine())
                        .score(existing.getScore() + rrfScore)
                        .graphRank(existing.getGraphRank())
                        .retrievalRank(i + 1)
                        .build();
                itemMap.put(key, merged);
            } else {
                HybridResult.HybridItem item = HybridResult.HybridItem.builder()
                        .source("RETRIEVAL")
                        .id(chunk.getId())
                        .name(chunk.getSymbol())
                        .content(chunk.getContent())
                        .filePath(chunk.getFilePath())
                        .startLine(chunk.getStartLine())
                        .endLine(chunk.getEndLine())
                        .score(rrfScore)
                        .graphRank(-1)
                        .retrievalRank(i + 1)
                        .build();
                itemMap.put(key, item);
            }
        }
        
        // 按分数排序并限制数量
        return itemMap.values().stream()
                .sorted(Comparator.comparingDouble(HybridResult.HybridItem::getScore).reversed())
                .limit(query.getTopK())
                .collect(Collectors.toList());
    }
    
    /**
     * 加权平均融合
     */
    private List<HybridResult.HybridItem> fuseWithWeightedAverage(
            List<GraphResult.GraphEntity> graphEntities,
            List<RetrievalResult.CodeChunkResult> retrievalChunks,
            HybridQuery query) {
        
        List<HybridResult.HybridItem> items = new ArrayList<>();
        
        // 添加 Graph 结果
        for (int i = 0; i < graphEntities.size(); i++) {
            GraphResult.GraphEntity entity = graphEntities.get(i);
            double normalizedScore = 1.0 - (double) i / graphEntities.size();
            
            items.add(HybridResult.HybridItem.builder()
                    .source("GRAPH")
                    .id(entity.getId())
                    .name(entity.getName())
                    .filePath(entity.getFilePath())
                    .startLine(entity.getStartLine() != null ? entity.getStartLine() : 0)
                    .endLine(entity.getEndLine() != null ? entity.getEndLine() : 0)
                    .score(normalizedScore * query.getGraphWeight())
                    .graphRank(i + 1)
                    .retrievalRank(-1)
                    .build());
        }
        
        // 添加 Retrieval 结果
        for (int i = 0; i < retrievalChunks.size(); i++) {
            RetrievalResult.CodeChunkResult chunk = retrievalChunks.get(i);
            
            items.add(HybridResult.HybridItem.builder()
                    .source("RETRIEVAL")
                    .id(chunk.getId())
                    .name(chunk.getSymbol())
                    .content(chunk.getContent())
                    .filePath(chunk.getFilePath())
                    .startLine(chunk.getStartLine())
                    .endLine(chunk.getEndLine())
                    .score(chunk.getScore() * query.getRetrievalWeight())
                    .graphRank(-1)
                    .retrievalRank(i + 1)
                    .build());
        }
        
        return items.stream()
                .sorted(Comparator.comparingDouble(HybridResult.HybridItem::getScore).reversed())
                .limit(query.getTopK())
                .collect(Collectors.toList());
    }
    
    /**
     * 简单并集
     */
    private List<HybridResult.HybridItem> fuseWithUnion(
            List<GraphResult.GraphEntity> graphEntities,
            List<RetrievalResult.CodeChunkResult> retrievalChunks,
            HybridQuery query) {
        
        List<HybridResult.HybridItem> items = new ArrayList<>();
        
        for (GraphResult.GraphEntity entity : graphEntities) {
            items.add(HybridResult.HybridItem.builder()
                    .source("GRAPH")
                    .id(entity.getId())
                    .name(entity.getName())
                    .filePath(entity.getFilePath())
                    .startLine(entity.getStartLine() != null ? entity.getStartLine() : 0)
                    .endLine(entity.getEndLine() != null ? entity.getEndLine() : 0)
                    .score(1.0)
                    .build());
        }
        
        for (RetrievalResult.CodeChunkResult chunk : retrievalChunks) {
            items.add(HybridResult.HybridItem.builder()
                    .source("RETRIEVAL")
                    .id(chunk.getId())
                    .name(chunk.getSymbol())
                    .content(chunk.getContent())
                    .filePath(chunk.getFilePath())
                    .startLine(chunk.getStartLine())
                    .endLine(chunk.getEndLine())
                    .score(chunk.getScore())
                    .build());
        }
        
        return items.stream()
                .limit(query.getTopK())
                .collect(Collectors.toList());
    }
    
    // ==================== 内部数据类 ====================
    
    private static class GraphSearchResult {
        final List<GraphResult.GraphEntity> entities;
        final long elapsedMs;
        
        GraphSearchResult(List<GraphResult.GraphEntity> entities, long elapsedMs) {
            this.entities = entities != null ? entities : Collections.emptyList();
            this.elapsedMs = elapsedMs;
        }
    }
    
    private static class RetrievalSearchResult {
        final List<RetrievalResult.CodeChunkResult> chunks;
        final long elapsedMs;
        
        RetrievalSearchResult(List<RetrievalResult.CodeChunkResult> chunks, long elapsedMs) {
            this.chunks = chunks != null ? chunks : Collections.emptyList();
            this.elapsedMs = elapsedMs;
        }
    }
}
