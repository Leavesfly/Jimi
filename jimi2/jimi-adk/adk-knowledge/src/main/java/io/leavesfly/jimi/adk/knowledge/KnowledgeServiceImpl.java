package io.leavesfly.jimi.adk.knowledge;

import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.knowledge.KnowledgeService;
import io.leavesfly.jimi.adk.api.knowledge.query.*;
import io.leavesfly.jimi.adk.api.knowledge.result.*;
import io.leavesfly.jimi.adk.api.knowledge.spi.*;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识服务统一门面实现
 *
 * <p>职责：
 * - 协调 Graph、Retrieval、Memory、HybridSearch、Wiki 五个子服务
 * - 处理跨服务的复杂逻辑
 * - 提供统一的错误处理和日志
 * - 管理知识服务的生命周期
 */
@Slf4j
public class KnowledgeServiceImpl implements KnowledgeService {

    private final GraphService graphService;
    private final RagService retrievalService;
    private final MemoryService memoryService;
    private final WikiService wikiService;

    private volatile boolean initialized = false;

    public KnowledgeServiceImpl(
            GraphService graphService,
            RagService retrievalService,
            MemoryService memoryService,
            WikiService wikiService) {
        this.graphService = graphService;
        this.retrievalService = retrievalService;
        this.memoryService = memoryService;
        this.wikiService = wikiService;

        log.info("KnowledgeService 创建完成, 可用服务: graph={}, rag={}, memory={}, wiki={}",
                graphService != null, retrievalService != null, memoryService != null,
                wikiService != null);
    }


    @Override
    public Mono<Boolean> initialize(Runtime runtime) {
        if (initialized) {
            log.debug("KnowledgeService 已初始化，跳过重复初始化");
            return Mono.just(true);
        }

        log.info("开始初始化 KnowledgeService...");

        // 并行初始化所有子服务
        return Mono.zip(
                initializeGraphService(runtime),
                initializeRetrievalService(runtime),
                initializeMemoryService(runtime),
                initializeWikiService(runtime)
        ).map(tuple -> {
            boolean graphOk = tuple.getT1();
            boolean retrievalOk = tuple.getT2();
            boolean memoryOk = tuple.getT3();
            boolean wikiOk = tuple.getT4();

            initialized = true;

            log.info("KnowledgeService 初始化完成: graph={}, retrieval={}, memory={}, wiki={}",
                    graphOk, retrievalOk, memoryOk, wikiOk);

            return true;
        }).onErrorResume(e -> {
            log.error("KnowledgeService 初始化失败", e);
            return Mono.just(false);
        });
    }

    /**
     * 初始化 Graph 服务
     */
    private Mono<Boolean> initializeGraphService(Runtime runtime) {
        if (graphService == null) {
            log.debug("GraphService 未启用，跳过初始化");
            return Mono.just(true);
        }

        return graphService.initialize(runtime)
                .doOnSuccess(ok -> log.debug("GraphService 初始化成功: {}", ok))
                .onErrorResume(e -> {
                    log.warn("GraphService 初始化失败: {}", e.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * 初始化 Retrieval 服务
     */
    private Mono<Boolean> initializeRetrievalService(Runtime runtime) {
        if (retrievalService == null) {
            log.debug("RetrievalService 未启用，跳过初始化");
            return Mono.just(true);
        }

        return retrievalService.initialize(runtime)
                .doOnSuccess(ok -> log.debug("RetrievalService 初始化成功: {}", ok))
                .onErrorResume(e -> {
                    log.warn("RetrievalService 初始化失败: {}", e.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * 初始化 Memory 服务
     */
    private Mono<Boolean> initializeMemoryService(Runtime runtime) {
        if (memoryService == null) {
            log.debug("MemoryService 未启用，跳过初始化");
            return Mono.just(true);
        }

        return memoryService.initialize(runtime)
                .doOnSuccess(ok -> log.debug("MemoryService 初始化成功: {}", ok))
                .onErrorResume(e -> {
                    log.warn("MemoryService 初始化失败: {}", e.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * 初始化 Wiki 服务
     */
    private Mono<Boolean> initializeWikiService(Runtime runtime) {
        if (wikiService == null) {
            log.debug("WikiService 未启用，跳过初始化");
            return Mono.just(true);
        }

        return wikiService.initialize(runtime)
                .doOnSuccess(ok -> log.debug("WikiService 初始化成功: {}", ok))
                .onErrorResume(e -> {
                    log.warn("WikiService 初始化失败: {}", e.getMessage());
                    return Mono.just(false);
                });
    }

    @Override
    public Mono<UnifiedKnowledgeResult> unifiedSearch(UnifiedKnowledgeQuery query) {
        long startTime = System.currentTimeMillis();

        if (query == null || query.getKeyword() == null || query.getKeyword().trim().isEmpty()) {
            return Mono.just(UnifiedKnowledgeResult.error("搜索关键词不能为空"));
        }

        log.debug("执行统一知识搜索: keyword={}, scope={}",
                query.getKeyword(), query.getScope());

        // 并发执行各模块的搜索
        return Mono.zip(
                searchGraphIfEnabled(query),
                searchMemoryIfEnabled(query),
                searchRetrievalIfEnabled(query),
                searchWikiIfEnabled(query)
        ).map(tuple -> {
            GraphResult graphResult = tuple.getT1();
            MemoryResult memoryResult = tuple.getT2();
            RetrievalResult retrievalResult = tuple.getT3();
            WikiResult wikiResult = tuple.getT4();

            long elapsedMs = System.currentTimeMillis() - startTime;

            // 构建统一结果
            UnifiedKnowledgeResult result = UnifiedKnowledgeResult.success(
                    query.getKeyword(),
                    UnifiedKnowledgeResult.GraphSearchResult.from(graphResult),
                    UnifiedKnowledgeResult.MemorySearchResult.from(memoryResult),
                    UnifiedKnowledgeResult.RetrievalSearchResult.from(retrievalResult),
                    UnifiedKnowledgeResult.WikiSearchResult.from(wikiResult),
                    elapsedMs
            );

            // 应用排序策略
            result = applySortStrategy(result, query);

            log.info("统一知识搜索完成: 共 {} 条结果, 耗时 {}ms",
                    result.getTotalResults(), elapsedMs);

            return result;
        }).onErrorResume(e -> {
            log.error("统一知识搜索失败", e);
            return Mono.just(UnifiedKnowledgeResult.error(e.getMessage()));
        });
    }

    /**
     * 搜索 Graph（如果启用）
     */
    private Mono<GraphResult> searchGraphIfEnabled(UnifiedKnowledgeQuery query) {
        if (!query.getScope().isIncludeGraph() || graphService == null) {
            return Mono.just(GraphResult.builder().success(true).build());
        }

        GraphQuery graphQuery = GraphQuery.builder()
                .type(GraphQuery.QueryType.SEARCH_BY_SYMBOL)
                .keyword(query.getKeyword())
                .limit(query.getLimit().getGraphLimit())
                .build();

        return graphService.query(graphQuery)
                .onErrorResume(e -> {
                    log.warn("Graph 搜索失败: {}", e.getMessage());
                    return Mono.just(GraphResult.builder().success(true).build());
                });
    }

    /**
     * 搜索 Memory（如果启用）
     */
    private Mono<MemoryResult> searchMemoryIfEnabled(UnifiedKnowledgeQuery query) {
        if (!query.getScope().isIncludeMemory() || memoryService == null) {
            return Mono.just(MemoryResult.builder().success(true).build());
        }

        MemoryQuery memoryQuery = MemoryQuery.builder()
                .operation(MemoryQuery.OperationType.QUERY)
                .query(query.getKeyword())
                .limit(query.getLimit().getMemoryLimit())
                .build();

        return memoryService.query(memoryQuery)
                .onErrorResume(e -> {
                    log.warn("Memory 搜索失败: {}", e.getMessage());
                    return Mono.just(MemoryResult.builder().success(true).build());
                });
    }

    /**
     * 搜索 Retrieval（如果启用）
     */
    private Mono<RetrievalResult> searchRetrievalIfEnabled(UnifiedKnowledgeQuery query) {
        if (!query.getScope().isIncludeRetrieval() || retrievalService == null) {
            return Mono.just(RetrievalResult.builder().success(true).build());
        }

        RetrievalQuery retrievalQuery = RetrievalQuery.builder()
                .query(query.getKeyword())
                .topK(query.getLimit().getRetrievalLimit())
                .build();

        return retrievalService.retrieve(retrievalQuery)
                .onErrorResume(e -> {
                    log.warn("Retrieval 搜索失败: {}", e.getMessage());
                    return Mono.just(RetrievalResult.builder().success(true).build());
                });
    }

    /**
     * 搜索 Wiki（如果启用）
     */
    private Mono<WikiResult> searchWikiIfEnabled(UnifiedKnowledgeQuery query) {
        if (!query.getScope().isIncludeWiki() || wikiService == null) {
            return Mono.just(WikiResult.builder().success(true).build());
        }

        WikiQuery wikiQuery = WikiQuery.builder()
                .operation(WikiQuery.OperationType.SEARCH)
                .keyword(query.getKeyword())
                .limit(query.getLimit().getWikiLimit())
                .projectRoot(query.getProjectRoot())
                .build();

        return wikiService.search(wikiQuery)
                .onErrorResume(e -> {
                    log.warn("Wiki 搜索失败: {}", e.getMessage());
                    return Mono.just(WikiResult.builder().success(true).build());
                });
    }

    /**
     * 应用排序策略
     */
    private UnifiedKnowledgeResult applySortStrategy(UnifiedKnowledgeResult result, UnifiedKnowledgeQuery query) {
        if (query.getSortStrategy() == null || query.getSortStrategy() == UnifiedKnowledgeQuery.SortStrategy.RELEVANCE) {
            return sortByRelevance(result, query);
        }

        switch (query.getSortStrategy()) {
            case TIME_DESC:
                return sortByTime(result);
            case PATH:
                return sortByPath(result);
            case TYPE:
                return sortByType(result);
            default:
                return result;
        }
    }

    /**
     * 按相关度排序（默认）
     */
    private UnifiedKnowledgeResult sortByRelevance(UnifiedKnowledgeResult result, UnifiedKnowledgeQuery query) {
        String keyword = query.getKeyword().toLowerCase();

        // Graph 结果排序（按名称匹配度）
        List<GraphResult.GraphEntity> sortedGraphEntities = result.getGraphResult().getEntities().stream()
                .sorted(Comparator.comparingDouble((GraphResult.GraphEntity e) ->
                        calculateRelevanceScore(keyword, e.getName(), e.getQualifiedName())).reversed())
                .collect(Collectors.toList());
        result.getGraphResult().setEntities(sortedGraphEntities);

        // Memory 结果排序（已有 relevanceScore）
        List<MemoryResult.MemoryEntry> sortedMemoryEntries = result.getMemoryResult().getEntries().stream()
                .sorted(Comparator.comparingDouble(MemoryResult.MemoryEntry::getRelevanceScore).reversed())
                .collect(Collectors.toList());
        result.getMemoryResult().setEntries(sortedMemoryEntries);

        // Retrieval 结果排序（已有 score）
        List<RetrievalResult.CodeChunkResult> sortedChunks = result.getRetrievalResult().getChunks().stream()
                .sorted(Comparator.comparingDouble(RetrievalResult.CodeChunkResult::getScore).reversed())
                .collect(Collectors.toList());
        result.getRetrievalResult().setChunks(sortedChunks);

        // Wiki 结果排序（按标题匹配度）
        List<WikiResult.WikiDocument> sortedWikiDocs = result.getWikiResult().getDocuments().stream()
                .sorted(Comparator.comparingDouble((WikiResult.WikiDocument d) ->
                        calculateRelevanceScore(keyword, d.getTitle(), d.getSummary())).reversed())
                .collect(Collectors.toList());
        result.getWikiResult().setDocuments(sortedWikiDocs);

        return result;
    }

    /**
     * 计算相关度分数
     */
    private double calculateRelevanceScore(String keyword, String name, String content) {
        if (name == null && content == null) {
            return 0.0;
        }

        String lowerName = name != null ? name.toLowerCase() : "";
        String lowerContent = content != null ? content.toLowerCase() : "";

        double score = 0.0;

        // 1. 精确匹配（最高权重）
        if (lowerName.equals(keyword)) {
            score += 100.0;
        } else if (lowerContent.equals(keyword)) {
            score += 80.0;
        }

        // 2. 包含匹配
        if (lowerName.contains(keyword)) {
            score += 50.0;
        }
        if (lowerContent.contains(keyword)) {
            score += 30.0;
        }

        // 3. 开头匹配
        if (lowerName.startsWith(keyword)) {
            score += 20.0;
        }

        // 4. 单词匹配（分词后匹配）
        String[] keywords = keyword.split("\\s+");
        for (String kw : keywords) {
            if (lowerName.contains(kw)) {
                score += 10.0;
            }
            if (lowerContent.contains(kw)) {
                score += 5.0;
            }
        }

        // 5. 长度惩罚（偏好简短结果）
        int nameLength = name != null ? name.length() : 0;
        if (nameLength > 0) {
            score = score * (1.0 - Math.min(nameLength / 1000.0, 0.3));
        }

        return score;
    }

    /**
     * 按时间排序
     */
    private UnifiedKnowledgeResult sortByTime(UnifiedKnowledgeResult result) {
        // Memory 按更新时间排序
        List<MemoryResult.MemoryEntry> sortedMemoryEntries = result.getMemoryResult().getEntries().stream()
                .sorted(Comparator.comparing(MemoryResult.MemoryEntry::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        result.getMemoryResult().setEntries(sortedMemoryEntries);

        // Wiki 按更新时间排序
        List<WikiResult.WikiDocument> sortedWikiDocs = result.getWikiResult().getDocuments().stream()
                .sorted(Comparator.comparingLong(WikiResult.WikiDocument::getLastUpdated).reversed())
                .collect(Collectors.toList());
        result.getWikiResult().setDocuments(sortedWikiDocs);

        return result;
    }

    /**
     * 按路径排序
     */
    private UnifiedKnowledgeResult sortByPath(UnifiedKnowledgeResult result) {
        // Graph 按文件路径排序
        List<GraphResult.GraphEntity> sortedGraphEntities = result.getGraphResult().getEntities().stream()
                .sorted(Comparator.comparing(GraphResult.GraphEntity::getFilePath,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
        result.getGraphResult().setEntities(sortedGraphEntities);

        // Retrieval 按文件路径排序
        List<RetrievalResult.CodeChunkResult> sortedChunks = result.getRetrievalResult().getChunks().stream()
                .sorted(Comparator.comparing(RetrievalResult.CodeChunkResult::getFilePath,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
        result.getRetrievalResult().setChunks(sortedChunks);

        // Wiki 按路径排序
        List<WikiResult.WikiDocument> sortedWikiDocs = result.getWikiResult().getDocuments().stream()
                .sorted(Comparator.comparing(d -> d.getPath() != null ? d.getPath().toString() : "",
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
        result.getWikiResult().setDocuments(sortedWikiDocs);

        return result;
    }

    /**
     * 按类型排序
     */
    private UnifiedKnowledgeResult sortByType(UnifiedKnowledgeResult result) {
        // Graph 按实体类型排序（CLASS > METHOD > FIELD）
        List<GraphResult.GraphEntity> sortedGraphEntities = result.getGraphResult().getEntities().stream()
                .sorted(Comparator.comparing(GraphResult.GraphEntity::getType,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
        result.getGraphResult().setEntities(sortedGraphEntities);

        // Memory 按记忆类型排序
        List<MemoryResult.MemoryEntry> sortedMemoryEntries = result.getMemoryResult().getEntries().stream()
                .sorted(Comparator.comparing(MemoryResult.MemoryEntry::getType,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
        result.getMemoryResult().setEntries(sortedMemoryEntries);

        // Retrieval 按编程语言排序
        List<RetrievalResult.CodeChunkResult> sortedChunks = result.getRetrievalResult().getChunks().stream()
                .sorted(Comparator.comparing(RetrievalResult.CodeChunkResult::getLanguage,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
        result.getRetrievalResult().setChunks(sortedChunks);

        return result;
    }
}
