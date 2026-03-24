package io.leavesfly.jimi.knowledge;

import io.leavesfly.jimi.core.engine.runtime.Runtime;
import io.leavesfly.jimi.knowledge.domain.query.*;
import io.leavesfly.jimi.knowledge.domain.result.*;
import io.leavesfly.jimi.knowledge.spi.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

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
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    private final GraphService graphService;
    private final RagService retrievalService;
    private final MemoryService memoryService;
    private final WikiService wikiService;

    private volatile boolean initialized = false;

    @Autowired
    public KnowledgeServiceImpl(
            @Autowired(required = false) GraphService graphService,
            @Autowired(required = false) RagService retrievalService,
            @Autowired(required = false) MemoryService memoryService,
            @Autowired(required = false) HybridSearchService hybridSearchService,
            @Autowired(required = false) WikiService wikiService) {
        this.graphService = graphService;
        this.retrievalService = retrievalService;
        this.memoryService = memoryService;
        this.wikiService = wikiService;

        log.info("KnowledgeService 创建完成, 可用服务: graph={}, rag={}, memory={}, hybrid={}, wiki={}",
                graphService != null, retrievalService != null, memoryService != null,
                hybridSearchService != null, wikiService != null);
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
                initializeServiceSafely("GraphService", graphService, runtime, graphService::initialize),
                initializeServiceSafely("RagService", retrievalService, runtime, retrievalService::initialize),
                initializeServiceSafely("MemoryService", memoryService, runtime, memoryService::initialize),
                initializeServiceSafely("WikiService", wikiService, runtime, wikiService::initialize)
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
     * 通用的服务初始化方法
     *
     * @param serviceName 服务名称，用于日志
     * @param service 服务实例，如果为 null 则跳过初始化
     * @param runtime 运行时环境
     * @param initializer 初始化函数
     * @return 初始化结果
     */
    private Mono<Boolean> initializeServiceSafely(String serviceName, Object service, Runtime runtime,
            java.util.function.Function<Runtime, Mono<Boolean>> initializer) {
        if (service == null) {
            log.debug("{} 未启用，跳过初始化", serviceName);
            return Mono.just(true);
        }
        return initializer.apply(runtime)
                .doOnSuccess(ok -> log.debug("{} 初始化成功: {}", serviceName, ok))
                .onErrorResume(e -> {
                    log.warn("{} 初始化失败: {}", serviceName, e.getMessage());
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
     * CLI 场景下不需要排序，直接返回结果
     */
    private UnifiedKnowledgeResult applySortStrategy(UnifiedKnowledgeResult result, UnifiedKnowledgeQuery query) {
        return result;
    }
}
