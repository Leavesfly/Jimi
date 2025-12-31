package io.leavesfly.jimi.tool.core.graph;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.knowledge.domain.query.HybridQuery;
import io.leavesfly.jimi.knowledge.domain.result.HybridResult;
import io.leavesfly.jimi.knowledge.spi.HybridSearchService;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

/**
 * 代码定位工具
 * <p>
 * 使用混合检索服务进行精准代码定位,支持:
 * - 符号名称搜索
 * - 文件路径搜索
 * - 自然语言描述搜索
 * - 多模式智能融合
 */
@Slf4j
public class CodeLocateTool extends AbstractTool<CodeLocateTool.Params> {
    
    private final HybridSearchService hybridSearchService;
    
    public CodeLocateTool(HybridSearchService hybridSearchService) {
        super(
            "CodeLocateTool",
            "精准代码定位工具。支持符号名称、文件路径、自然语言描述等多种查询方式，自动选择最佳检索策略。",
            Params.class
        );
        this.hybridSearchService = hybridSearchService;
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        log.info("CodeLocate tool called: query='{}', mode={}, topK={}", 
                params.getQuery(), params.getMode(), params.getTopK());
        
        // 构建查询
        HybridQuery.HybridQueryBuilder queryBuilder = HybridQuery.builder()
                .keyword(params.getQuery())
                .topK(params.getTopK())
                .graphWeight(params.getGraphWeight())
                .retrievalWeight(params.getVectorWeight());
        
        // 根据融合策略设置
        HybridQuery.FusionStrategy strategy = parseFusionStrategy(params.getFusionStrategy());
        queryBuilder.strategy(strategy);
        
        HybridQuery query = queryBuilder.build();
        
        // 根据模式选择检索策略
        Mono<HybridResult> resultMono;
        switch (params.getMode()) {
            case GRAPH_ONLY:
                resultMono = hybridSearchService.searchGraphOnly(query);
                break;
            case VECTOR_ONLY:
                resultMono = hybridSearchService.searchRetrievalOnly(query);
                break;
            case HYBRID:
            case SMART:
            default:
                resultMono = hybridSearchService.search(query);
                break;
        }
        
        return resultMono
                .map(result -> {
                    if (!result.isSuccess()) {
                        return ToolResult.error(
                                result.getErrorMessage() != null ? result.getErrorMessage() : "Search failed",
                                "Search failed"
                        );
                    }
                    
                    // 格式化输出
                    String output = formatSearchResult(result, params);
                    return ToolResult.ok(output, "Code location completed");
                })
                .onErrorResume(e -> {
                    log.error("CodeLocate failed: {}", e.getMessage(), e);
                    return Mono.just(ToolResult.error(
                            "Code location failed: " + e.getMessage(),
                            "Execution error"
                    ));
                });
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 解析融合策略
     */
    private HybridQuery.FusionStrategy parseFusionStrategy(String strategyStr) {
        if (strategyStr == null || strategyStr.trim().isEmpty()) {
            return HybridQuery.FusionStrategy.RRF;
        }
        
        try {
            return HybridQuery.FusionStrategy.valueOf(strategyStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return HybridQuery.FusionStrategy.RRF;
        }
    }
    
    /**
     * 格式化搜索结果
     */
    private String formatSearchResult(HybridResult result, Params params) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("# 代码定位结果\n\n");
        sb.append(String.format("**查询**: %s\n", params.getQuery()));
        sb.append(String.format("**模式**: %s\n", params.getMode()));
        
        int totalResults = result.getMergedResults().size();
        sb.append(String.format("**找到**: %d 个相关代码片段\n", totalResults));
        sb.append(String.format("**耗时**: %d ms\n\n", result.getTotalElapsedMs()));
        
        if (totalResults == 0) {
            sb.append("❌ 未找到匹配的代码片段。请尝试:\n");
            sb.append("- 使用更简洁的关键词\n");
            sb.append("- 切换到其他模式 (smart/hybrid/graph_only/vector_only)\n");
            sb.append("- 增加 topK 参数\n");
            return sb.toString();
        }
        
        // 结果详情
        int index = 1;
        for (HybridResult.HybridItem item : result.getMergedResults()) {
            sb.append(String.format("## %d. ", index));
            sb.append(String.format("`%s`\n", item.getName()));
            
            if (item.getFilePath() != null) {
                sb.append(String.format("- **位置**: %s", item.getFilePath()));
                if (item.getStartLine() > 0) {
                    sb.append(String.format(":%d", item.getStartLine()));
                }
                sb.append("\n");
            }
            
            // 分数信息
            sb.append(String.format("- **分数**: %.4f", item.getScore()));
            sb.append(String.format(" (来源: %s)", item.getSource()));
            sb.append("\n\n");
            
            index++;
        }
        
        return sb.toString();
    }
    
    // ==================== 数据模型 ====================
    
    /**
     * 搜索模式
     */
    public enum SearchMode {
        SMART,         // 智能模式 (自动分析)
        HYBRID,        // 混合模式 (图+向量)
        GRAPH_ONLY,    // 仅图检索
        VECTOR_ONLY    // 仅向量检索
    }
    
    /**
     * 工具参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        
        @JsonPropertyDescription("查询文本。支持符号名称(如'UserService')、文件路径(如'UserService.java')、或自然语言描述(如'用户认证相关的代码')")
        private String query;
        
        @JsonPropertyDescription("搜索模式。可选值: smart(智能自动选择), hybrid(混合检索), graph_only(仅图检索), vector_only(仅向量检索)。默认: smart")
        @Builder.Default
        private SearchMode mode = SearchMode.SMART;
        
        @JsonPropertyDescription("返回结果数量。默认: 5")
        @Builder.Default
        private int topK = 5;
        
        @JsonPropertyDescription("图检索权重 (0-1)。仅在 hybrid 模式有效。默认: 0.5")
        @Builder.Default
        private double graphWeight = 0.5;
        
        @JsonPropertyDescription("向量检索权重 (0-1)。仅在 hybrid 模式有效。默认: 0.5")
        @Builder.Default
        private double vectorWeight = 0.5;
        
        @JsonPropertyDescription("融合策略。可选值: WEIGHTED_SUM, RRF, MAX, MULTIPLICATIVE。默认: WEIGHTED_SUM")
        @Builder.Default
        private String fusionStrategy = "WEIGHTED_SUM";
        
        @JsonPropertyDescription("多源加成系数。当结果同时出现在图和向量检索中时的加分。默认: 1.2")
        @Builder.Default
        private double multiSourceBonus = 1.2;
        
        @JsonPropertyDescription("实体类型过滤。逗号分隔,如'CLASS,METHOD,INTERFACE'。默认: 不过滤")
        private String entityTypes;
        
        @JsonPropertyDescription("关系类型过滤。逗号分隔,如'CALLS,EXTENDS,IMPLEMENTS'。默认: 不过滤")
        private String relationTypes;
        
        @JsonPropertyDescription("是否包含相关实体。默认: false")
        @Builder.Default
        private boolean includeRelated = false;
    }
}
