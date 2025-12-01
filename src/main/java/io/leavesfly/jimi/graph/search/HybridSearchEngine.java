package io.leavesfly.jimi.graph.search;

import io.leavesfly.jimi.graph.model.CodeEntity;
import io.leavesfly.jimi.retrieval.CodeChunk;
import io.leavesfly.jimi.retrieval.EmbeddingProvider;
import io.leavesfly.jimi.retrieval.VectorStore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索引擎
 * <p>
 * 融合图检索和向量检索的结果,提供更精准的代码定位:
 * - 图检索: 结构化、符号精确匹配
 * - 向量检索: 语义理解、上下文相似
 * - 结果融合: 互补优势、重排序
 */
@Slf4j
@Component
public class HybridSearchEngine {
    
    private final GraphSearchEngine graphSearchEngine;
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    
    public HybridSearchEngine(GraphSearchEngine graphSearchEngine,
                             @Autowired(required = false) VectorStore vectorStore,
                             @Autowired(required = false) EmbeddingProvider embeddingProvider) {
        this.graphSearchEngine = graphSearchEngine;
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
    }
    
    /**
     * 混合搜索: 同时执行图检索和向量检索,融合结果
     *
     * @param query 查询文本
     * @param config 混合搜索配置
     * @return 混合搜索结果
     */
    public Mono<HybridSearchResult> search(String query, HybridSearchConfig config) {
        return Mono.fromCallable(() -> {
            long startTime = System.currentTimeMillis();
            HybridSearchResult result = new HybridSearchResult();
            result.setQuery(query);
            result.setConfig(config);
            
            // 检查向量检索是否可用
            if (config.isEnableVectorSearch() && (vectorStore == null || embeddingProvider == null)) {
                log.warn("Vector search requested but VectorStore or EmbeddingProvider not available, disabling vector search");
                config.setEnableVectorSearch(false);
            }
            
            // 1. 图检索
            GraphSearchEngine.GraphSearchResult graphResult = null;
            if (config.isEnableGraphSearch()) {
                graphResult = performGraphSearch(query, config).block();
                result.setGraphSearchResult(graphResult);
            }
            
            // 2. 向量检索
            List<VectorStore.SearchResult> vectorResults = null;
            if (config.isEnableVectorSearch()) {
                vectorResults = performVectorSearch(query, config).block();
                result.setVectorSearchResults(vectorResults);
            }
            
            // 3. 结果融合
            List<HybridResult> fusedResults = fuseResults(
                graphResult, 
                vectorResults, 
                config
            );
            
            result.setFusedResults(fusedResults);
            result.setTotalResults(fusedResults.size());
            result.setElapsedMs(System.currentTimeMillis() - startTime);
            result.setSuccess(true);
            
            log.info("Hybrid search completed: {} results (graph:{}, vector:{}) in {}ms",
                    fusedResults.size(),
                    graphResult != null ? graphResult.getTotalResults() : 0,
                    vectorResults != null ? vectorResults.size() : 0,
                    result.getElapsedMs());
            
            return result;
        });
    }
    
    /**
     * 智能搜索: 自动判断查询类型并选择最佳检索策略
     *
     * @param query 查询文本
     * @param topK 返回数量
     * @return 混合搜索结果
     */
    public Mono<HybridSearchResult> smartSearch(String query, int topK) {
        return Mono.fromCallable(() -> {
            // 分析查询特征
            QueryAnalysis analysis = analyzeQuery(query);
            
            // 根据分析结果构建配置
            HybridSearchConfig config = buildSmartConfig(analysis, topK);
            
            // 如果向量检索不可用,自动调整配置
            if (config.isEnableVectorSearch() && (vectorStore == null || embeddingProvider == null)) {
                log.debug("Vector search not available, using graph search only");
                config.setEnableVectorSearch(false);
                config.setEnableGraphSearch(true);
                config.setGraphWeight(1.0);
                config.setVectorWeight(0.0);
            }
            
            log.debug("Smart search strategy: graph={}, vector={}, weights={}/{}",
                    config.isEnableGraphSearch(),
                    config.isEnableVectorSearch(),
                    config.getGraphWeight(),
                    config.getVectorWeight());
            
            return search(query, config).block();
        });
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 执行图检索
     */
    private Mono<GraphSearchEngine.GraphSearchResult> performGraphSearch(
            String query, HybridSearchConfig config) {
        
        // 构建上下文查询
        GraphSearchEngine.ContextQuery contextQuery = GraphSearchEngine.ContextQuery.builder()
            .description(query)
            .symbols(extractSymbols(query))
            .filePaths(extractFilePaths(query))
            .entityTypes(config.getEntityTypes())
            .relationTypes(config.getRelationTypes())
            .includeRelated(config.isIncludeRelated())
            .limit(config.getGraphTopK())
            .build();
        
        return graphSearchEngine.searchByContext(contextQuery);
    }
    
    /**
     * 执行向量检索
     */
    private Mono<List<VectorStore.SearchResult>> performVectorSearch(
            String query, HybridSearchConfig config) {
        
        return embeddingProvider.embed(query)
            .flatMap(queryVector -> vectorStore.search(
                queryVector, 
                config.getVectorTopK(),
                config.getSearchFilter()
            ));
    }
    
    /**
     * 融合图检索和向量检索结果
     */
    private List<HybridResult> fuseResults(
            GraphSearchEngine.GraphSearchResult graphResult,
            List<VectorStore.SearchResult> vectorResults,
            HybridSearchConfig config) {
        
        Map<String, HybridResult> resultMap = new HashMap<>();
        
        // 1. 处理图检索结果
        if (graphResult != null && graphResult.getSuccess()) {
            for (GraphSearchEngine.ScoredEntity scored : graphResult.getResults()) {
                String key = generateKey(scored.getEntity());
                
                HybridResult hybrid = resultMap.computeIfAbsent(key, k -> new HybridResult());
                hybrid.setEntity(scored.getEntity());
                hybrid.setGraphScore(scored.getScore());
                hybrid.setGraphReason(scored.getReason());
                hybrid.setSources(new HashSet<>(Arrays.asList(ResultSource.GRAPH)));
            }
        }
        
        // 2. 处理向量检索结果
        if (vectorResults != null) {
            for (VectorStore.SearchResult vectorResult : vectorResults) {
                CodeChunk chunk = vectorResult.getChunk();
                
                // 尝试找到对应的图实体
                CodeEntity matchedEntity = findMatchingEntity(chunk);
                
                if (matchedEntity != null) {
                    String key = generateKey(matchedEntity);
                    
                    HybridResult hybrid = resultMap.computeIfAbsent(key, k -> new HybridResult());
                    hybrid.setEntity(matchedEntity);
                    hybrid.setVectorScore(vectorResult.getScore());
                    hybrid.setCodeChunk(chunk);
                    
                    if (hybrid.getSources() == null) {
                        hybrid.setSources(new HashSet<>());
                    }
                    hybrid.getSources().add(ResultSource.VECTOR);
                } else {
                    // 向量结果没有对应的图实体,创建仅向量结果
                    String key = "vector_" + chunk.getId();
                    
                    HybridResult hybrid = new HybridResult();
                    hybrid.setVectorScore(vectorResult.getScore());
                    hybrid.setCodeChunk(chunk);
                    hybrid.setSources(new HashSet<>(Arrays.asList(ResultSource.VECTOR)));
                    
                    resultMap.put(key, hybrid);
                }
            }
        }
        
        // 3. 计算融合分数
        for (HybridResult hybrid : resultMap.values()) {
            double fusedScore = calculateFusedScore(hybrid, config);
            hybrid.setFusedScore(fusedScore);
        }
        
        // 4. 排序并返回TopK
        List<HybridResult> sortedResults = new ArrayList<>(resultMap.values());
        sortedResults.sort((a, b) -> Double.compare(b.getFusedScore(), a.getFusedScore()));
        
        int limit = Math.min(config.getFinalTopK(), sortedResults.size());
        return sortedResults.subList(0, limit);
    }
    
    /**
     * 计算融合分数
     */
    private double calculateFusedScore(HybridResult hybrid, HybridSearchConfig config) {
        double graphScore = hybrid.getGraphScore() != null ? hybrid.getGraphScore() : 0.0;
        double vectorScore = hybrid.getVectorScore() != null ? hybrid.getVectorScore() : 0.0;
        
        double fusedScore = 0.0;
        
        switch (config.getFusionStrategy()) {
            case WEIGHTED_SUM:
                fusedScore = (graphScore * config.getGraphWeight()) + 
                            (vectorScore * config.getVectorWeight());
                break;
                
            case RRF: // Reciprocal Rank Fusion
                double graphRank = graphScore > 0 ? 1.0 / (1.0 + graphScore) : 0.0;
                double vectorRank = vectorScore > 0 ? 1.0 / (1.0 + vectorScore) : 0.0;
                fusedScore = graphRank + vectorRank;
                break;
                
            case MAX:
                fusedScore = Math.max(graphScore, vectorScore);
                break;
                
            case MULTIPLICATIVE:
                if (graphScore > 0 && vectorScore > 0) {
                    fusedScore = graphScore * vectorScore;
                } else {
                    fusedScore = Math.max(graphScore, vectorScore);
                }
                break;
        }
        
        // Bonus: 如果同时出现在两个结果中,额外加分
        if (hybrid.getSources() != null && hybrid.getSources().size() > 1) {
            fusedScore *= config.getMultiSourceBonus();
        }
        
        return fusedScore;
    }
    
    /**
     * 从查询中提取符号
     */
    private List<String> extractSymbols(String query) {
        List<String> symbols = new ArrayList<>();
        
        // 简单的启发式规则:
        // 1. 驼峰命名的词
        // 2. 包含大写字母的词
        String[] words = query.split("\\s+");
        for (String word : words) {
            if (word.matches(".*[A-Z].*")) {
                symbols.add(word);
            }
        }
        
        return symbols;
    }
    
    /**
     * 从查询中提取文件路径
     */
    private List<String> extractFilePaths(String query) {
        List<String> paths = new ArrayList<>();
        
        // 简单的启发式规则:
        // 1. 包含 "/" 或 "."
        // 2. 包含文件扩展名
        String[] words = query.split("\\s+");
        for (String word : words) {
            if (word.contains("/") || word.contains("\\") || 
                word.matches(".*\\.(java|kt|py|js|ts).*")) {
                paths.add(word);
            }
        }
        
        return paths;
    }
    
    /**
     * 查找匹配的图实体
     */
    private CodeEntity findMatchingEntity(CodeChunk chunk) {
        // 简化实现: 通过文件路径 + 符号名称匹配
        // 实际应用中可以使用更复杂的匹配逻辑
        
        // TODO: 实现更精确的 CodeChunk -> CodeEntity 映射
        return null;
    }
    
    /**
     * 生成实体唯一键
     */
    private String generateKey(CodeEntity entity) {
        return entity.getId();
    }
    
    /**
     * 分析查询特征
     */
    private QueryAnalysis analyzeQuery(String query) {
        QueryAnalysis analysis = new QueryAnalysis();
        
        // 检查是否包含符号名称
        boolean hasSymbols = query.matches(".*[A-Z][a-z]+.*");
        analysis.setHasSymbols(hasSymbols);
        
        // 检查是否包含文件路径
        boolean hasFilePath = query.contains("/") || query.contains("\\");
        analysis.setHasFilePath(hasFilePath);
        
        // 检查是否是自然语言描述
        boolean isNaturalLanguage = query.split("\\s+").length > 3 && 
                                   !hasSymbols && !hasFilePath;
        analysis.setNaturalLanguage(isNaturalLanguage);
        
        // 查询长度
        analysis.setQueryLength(query.length());
        
        return analysis;
    }
    
    /**
     * 根据查询分析构建智能配置
     */
    private HybridSearchConfig buildSmartConfig(QueryAnalysis analysis, int topK) {
        HybridSearchConfig config = new HybridSearchConfig();
        config.setFinalTopK(topK);
        
        if (analysis.isHasSymbols() || analysis.isHasFilePath()) {
            // 结构化查询: 优先使用图检索
            config.setEnableGraphSearch(true);
            config.setEnableVectorSearch(true);
            config.setGraphWeight(0.7);
            config.setVectorWeight(0.3);
            config.setGraphTopK(topK * 2);
            config.setVectorTopK(topK);
        } else if (analysis.isNaturalLanguage()) {
            // 自然语言查询: 优先使用向量检索
            config.setEnableGraphSearch(false);
            config.setEnableVectorSearch(true);
            config.setGraphWeight(0.3);
            config.setVectorWeight(0.7);
            config.setGraphTopK(topK);
            config.setVectorTopK(topK * 2);
        } else {
            // 混合查询: 均衡权重
            config.setEnableGraphSearch(true);
            config.setEnableVectorSearch(true);
            config.setGraphWeight(0.5);
            config.setVectorWeight(0.5);
            config.setGraphTopK(topK);
            config.setVectorTopK(topK);
        }
        
        config.setFusionStrategy(FusionStrategy.WEIGHTED_SUM);
        config.setMultiSourceBonus(1.2);
        
        return config;
    }
    
    // ==================== 数据模型 ====================
    
    /**
     * 结果来源
     */
    public enum ResultSource {
        GRAPH,    // 图检索
        VECTOR    // 向量检索
    }
    
    /**
     * 融合策略
     */
    public enum FusionStrategy {
        WEIGHTED_SUM,      // 加权和
        RRF,               // 倒数排名融合
        MAX,               // 取最大值
        MULTIPLICATIVE     // 乘法融合
    }
    
    /**
     * 混合搜索配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HybridSearchConfig {
        private boolean enableGraphSearch = true;
        private boolean enableVectorSearch = true;
        
        private int graphTopK = 10;
        private int vectorTopK = 10;
        private int finalTopK = 10;
        
        private double graphWeight = 0.5;
        private double vectorWeight = 0.5;
        
        private FusionStrategy fusionStrategy = FusionStrategy.WEIGHTED_SUM;
        private double multiSourceBonus = 1.2; // 多源加成
        
        // 图检索配置
        private Set<io.leavesfly.jimi.graph.model.EntityType> entityTypes;
        private Set<io.leavesfly.jimi.graph.model.RelationType> relationTypes;
        private boolean includeRelated = false;
        
        // 向量检索配置
        private VectorStore.SearchFilter searchFilter;
    }
    
    /**
     * 混合结果
     */
    @Data
    public static class HybridResult {
        private CodeEntity entity;        // 图实体
        private CodeChunk codeChunk;      // 代码片段
        
        private Double graphScore;        // 图检索分数
        private Double vectorScore;       // 向量检索分数
        private Double fusedScore;        // 融合分数
        
        private String graphReason;       // 图检索原因
        private Set<ResultSource> sources; // 结果来源
    }
    
    /**
     * 混合搜索结果
     */
    @Data
    public static class HybridSearchResult {
        private String query;
        private HybridSearchConfig config;
        
        private GraphSearchEngine.GraphSearchResult graphSearchResult;
        private List<VectorStore.SearchResult> vectorSearchResults;
        
        private List<HybridResult> fusedResults;
        private Integer totalResults;
        private Long elapsedMs;
        private Boolean success;
        private String errorMessage;
    }
    
    /**
     * 查询分析
     */
    @Data
    private static class QueryAnalysis {
        private boolean hasSymbols;
        private boolean hasFilePath;
        private boolean isNaturalLanguage;
        private int queryLength;
    }
}
