package io.leavesfly.jimi.tool.graph;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.graph.GraphManager;
import io.leavesfly.jimi.graph.model.EntityType;
import io.leavesfly.jimi.graph.model.RelationType;
import io.leavesfly.jimi.graph.search.GraphSearchEngine;
import io.leavesfly.jimi.graph.search.HybridSearchEngine;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 代码定位工具
 * <p>
 * 使用混合检索引擎进行精准代码定位,支持:
 * - 符号名称搜索
 * - 文件路径搜索
 * - 自然语言描述搜索
 * - 多模式智能融合
 */
@Slf4j
public class CodeLocateTool extends AbstractTool<CodeLocateTool.Params> {
    
    private final HybridSearchEngine hybridSearchEngine;
    private final GraphManager graphManager;
    
    public CodeLocateTool(HybridSearchEngine hybridSearchEngine, GraphManager graphManager) {
        super(
            "code_locate",
            "精准代码定位工具。支持符号名称、文件路径、自然语言描述等多种查询方式，自动选择最佳检索策略。",
            Params.class
        );
        this.hybridSearchEngine = hybridSearchEngine;
        this.graphManager = graphManager;
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        log.info("CodeLocate tool called: query='{}', mode={}, topK={}", 
                params.getQuery(), params.getMode(), params.getTopK());
        
        // 确保工作目录已初始化（触发自动加载）
        if (graphManager != null) {
            graphManager.ensureWorkDirInitialized();
        }
        
        try {
            // 根据模式选择检索策略
            HybridSearchEngine.HybridSearchResult result;
            
            switch (params.getMode()) {
                case SMART:
                    // 智能模式: 自动分析查询特征
                    result = hybridSearchEngine.smartSearch(
                        params.getQuery(),
                        params.getTopK()
                    ).block();
                    break;
                    
                case GRAPH_ONLY:
                    // 仅图检索模式
                    result = executeGraphOnlySearch(params).block();
                    break;
                    
                case VECTOR_ONLY:
                    // 仅向量检索模式
                    result = executeVectorOnlySearch(params).block();
                    break;
                    
                case HYBRID:
                default:
                    // 混合模式: 均衡图和向量
                    result = executeHybridSearch(params).block();
                    break;
            }
            
            if (result == null || !result.getSuccess()) {
                return Mono.just(ToolResult.error(
                    result != null ? result.getErrorMessage() : "Search failed",
                    "Search failed"
                ));
            }
            
            // 格式化输出
            String output = formatSearchResult(result, params);
            
            return Mono.just(ToolResult.ok(output, "Code location completed"));
            
        } catch (Exception e) {
            log.error("CodeLocate failed: {}", e.getMessage(), e);
            return Mono.just(ToolResult.error(
                "Code location failed: " + e.getMessage(),
                "Execution error"
            ));
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 执行纯图检索
     */
    private Mono<HybridSearchEngine.HybridSearchResult> executeGraphOnlySearch(Params params) {
        HybridSearchEngine.HybridSearchConfig config = HybridSearchEngine.HybridSearchConfig.builder()
            .enableGraphSearch(true)
            .enableVectorSearch(false)
            .graphTopK(params.getTopK())
            .finalTopK(params.getTopK())
            .graphWeight(1.0)
            .entityTypes(parseEntityTypes(params.getEntityTypes()))
            .relationTypes(parseRelationTypes(params.getRelationTypes()))
            .includeRelated(params.isIncludeRelated())
            .build();
        
        return hybridSearchEngine.search(params.getQuery(), config);
    }
    
    /**
     * 执行纯向量检索
     */
    private Mono<HybridSearchEngine.HybridSearchResult> executeVectorOnlySearch(Params params) {
        HybridSearchEngine.HybridSearchConfig config = HybridSearchEngine.HybridSearchConfig.builder()
            .enableGraphSearch(false)
            .enableVectorSearch(true)
            .vectorTopK(params.getTopK())
            .finalTopK(params.getTopK())
            .vectorWeight(1.0)
            .build();
        
        return hybridSearchEngine.search(params.getQuery(), config);
    }
    
    /**
     * 执行混合检索
     */
    private Mono<HybridSearchEngine.HybridSearchResult> executeHybridSearch(Params params) {
        HybridSearchEngine.HybridSearchConfig config = HybridSearchEngine.HybridSearchConfig.builder()
            .enableGraphSearch(true)
            .enableVectorSearch(true)
            .graphTopK(params.getTopK())
            .vectorTopK(params.getTopK())
            .finalTopK(params.getTopK())
            .graphWeight(params.getGraphWeight())
            .vectorWeight(params.getVectorWeight())
            .fusionStrategy(parseFusionStrategy(params.getFusionStrategy()))
            .multiSourceBonus(params.getMultiSourceBonus())
            .entityTypes(parseEntityTypes(params.getEntityTypes()))
            .relationTypes(parseRelationTypes(params.getRelationTypes()))
            .includeRelated(params.isIncludeRelated())
            .build();
        
        return hybridSearchEngine.search(params.getQuery(), config);
    }
    
    /**
     * 格式化搜索结果
     */
    private String formatSearchResult(HybridSearchEngine.HybridSearchResult result, Params params) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("# 代码定位结果\n\n");
        sb.append(String.format("**查询**: %s\n", params.getQuery()));
        sb.append(String.format("**模式**: %s\n", params.getMode()));
        sb.append(String.format("**找到**: %d 个相关代码片段\n", result.getTotalResults()));
        sb.append(String.format("**耗时**: %d ms\n\n", result.getElapsedMs()));
        
        if (result.getTotalResults() == 0) {
            sb.append("❌ 未找到匹配的代码片段。请尝试:\n");
            sb.append("- 使用更简洁的关键词\n");
            sb.append("- 切换到其他模式 (smart/hybrid/graph_only/vector_only)\n");
            sb.append("- 增加 topK 参数\n");
            return sb.toString();
        }
        
        // 结果详情
        int index = 1;
        for (HybridSearchEngine.HybridResult hybridResult : result.getFusedResults()) {
            sb.append(String.format("## %d. ", index));
            
            if (hybridResult.getEntity() != null) {
                var entity = hybridResult.getEntity();
                sb.append(String.format("`%s` (%s)\n", entity.getName(), entity.getType()));
                
                if (entity.getFilePath() != null) {
                    sb.append(String.format("- **位置**: %s", entity.getFilePath()));
                    if (entity.getStartLine() != null) {
                        sb.append(String.format(":%d", entity.getStartLine()));
                    }
                    sb.append("\n");
                }
                
                if (entity.getQualifiedName() != null) {
                    sb.append(String.format("- **完整名称**: %s\n", entity.getQualifiedName()));
                }
            }
            
            // 分数信息
            sb.append(String.format("- **分数**: %.4f", hybridResult.getFusedScore()));
            if (hybridResult.getSources() != null && !hybridResult.getSources().isEmpty()) {
                sb.append(" (来源: ");
                sb.append(hybridResult.getSources().stream()
                    .map(Enum::name)
                    .collect(Collectors.joining(" + ")));
                sb.append(")");
            }
            sb.append("\n\n");
            
            index++;
        }
        
        // 使用建议
        if (params.getMode() == SearchMode.SMART && result.getConfig() != null) {
            sb.append("---\n\n");
            sb.append("**智能策略分析**:\n");
            sb.append(String.format("- 图检索: %s (权重: %.2f)\n", 
                    result.getConfig().isEnableGraphSearch() ? "✅" : "❌",
                    result.getConfig().getGraphWeight()));
            sb.append(String.format("- 向量检索: %s (权重: %.2f)\n",
                    result.getConfig().isEnableVectorSearch() ? "✅" : "❌",
                    result.getConfig().getVectorWeight()));
        }
        
        return sb.toString();
    }
    
    /**
     * 解析实体类型
     */
    private Set<EntityType> parseEntityTypes(String entityTypesStr) {
        if (entityTypesStr == null || entityTypesStr.trim().isEmpty()) {
            return null;
        }
        
        return Arrays.stream(entityTypesStr.split(","))
            .map(String::trim)
            .map(String::toUpperCase)
            .map(EntityType::valueOf)
            .collect(Collectors.toSet());
    }
    
    /**
     * 解析关系类型
     */
    private Set<RelationType> parseRelationTypes(String relationTypesStr) {
        if (relationTypesStr == null || relationTypesStr.trim().isEmpty()) {
            return null;
        }
        
        return Arrays.stream(relationTypesStr.split(","))
            .map(String::trim)
            .map(String::toUpperCase)
            .map(RelationType::valueOf)
            .collect(Collectors.toSet());
    }
    
    /**
     * 解析融合策略
     */
    private HybridSearchEngine.FusionStrategy parseFusionStrategy(String strategyStr) {
        if (strategyStr == null || strategyStr.trim().isEmpty()) {
            return HybridSearchEngine.FusionStrategy.WEIGHTED_SUM;
        }
        
        return HybridSearchEngine.FusionStrategy.valueOf(strategyStr.toUpperCase());
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
