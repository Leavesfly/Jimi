package io.leavesfly.jimi.tool.graph;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.graph.navigator.ImpactAnalyzer;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 影响分析工具
 * <p>
 * 分析代码变更的影响范围
 */
@Slf4j
public class ImpactAnalysisTool extends AbstractTool<ImpactAnalysisTool.Params> {
    
    private final ImpactAnalyzer impactAnalyzer;
    
    public ImpactAnalysisTool(ImpactAnalyzer impactAnalyzer) {
        super(
            "impact_analysis",
            "分析代码变更的影响范围。可分析修改某个类、方法、文件后会影响哪些代码。",
            Params.class
        );
        this.impactAnalyzer = impactAnalyzer;
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        log.info("ImpactAnalysis tool called: entityId='{}', analysisType={}, maxDepth={}", 
                params.getEntityId(), params.getAnalysisType(), params.getMaxDepth());
        
        try {
            ImpactAnalyzer.AnalysisType type = ImpactAnalyzer.AnalysisType.valueOf(
                params.getAnalysisType().toUpperCase()
            );
            
            ImpactAnalyzer.ImpactAnalysisResult result = impactAnalyzer.analyzeImpact(
                params.getEntityId(),
                type,
                params.getMaxDepth()
            ).block();
            
            if (result == null || !result.getSuccess()) {
                return Mono.just(ToolResult.error(
                    result != null ? result.getErrorMessage() : "Analysis failed",
                    "Analysis failed"
                ));
            }
            
            String output = formatAnalysisResult(result);
            return Mono.just(ToolResult.ok(output, "Impact analysis completed"));
            
        } catch (Exception e) {
            log.error("Impact analysis failed: {}", e.getMessage(), e);
            return Mono.just(ToolResult.error(
                "Impact analysis failed: " + e.getMessage(),
                "Execution error"
            ));
        }
    }
    
    private String formatAnalysisResult(ImpactAnalyzer.ImpactAnalysisResult result) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("# 影响分析结果\n\n");
        sb.append(String.format("**目标**: %s\n", result.getTargetEntity() != null ? 
                result.getTargetEntity().getName() : result.getTargetEntityId()));
        sb.append(String.format("**分析类型**: %s\n", result.getAnalysisType()));
        sb.append(String.format("**最大深度**: %d\n\n", result.getMaxDepth()));
        
        if (result.getAnalysisType() == ImpactAnalyzer.AnalysisType.DOWNSTREAM ||
            result.getAnalysisType() == ImpactAnalyzer.AnalysisType.BOTH) {
            sb.append(String.format("## 下游影响 (%d个实体)\n\n", 
                    result.getDownstreamEntities().size()));
            
            result.getDownstreamByType().forEach((type, count) -> 
                sb.append(String.format("- %s: %d\n", type, count))
            );
            sb.append("\n");
        }
        
        if (result.getAnalysisType() == ImpactAnalyzer.AnalysisType.UPSTREAM ||
            result.getAnalysisType() == ImpactAnalyzer.AnalysisType.BOTH) {
            sb.append(String.format("## 上游依赖 (%d个实体)\n\n", 
                    result.getUpstreamEntities().size()));
            
            result.getUpstreamByType().forEach((type, count) -> 
                sb.append(String.format("- %s: %d\n", type, count))
            );
            sb.append("\n");
        }
        
        sb.append(String.format("**总影响范围**: %d 个实体\n", result.getTotalImpactedEntities()));
        
        return sb.toString();
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        
        @JsonPropertyDescription("要分析的实体ID,格式如 'CLASS:com.example.UserService'")
        private String entityId;
        
        @JsonPropertyDescription("分析类型。可选值: downstream(下游影响), upstream(上游依赖), both(双向)。默认: downstream")
        @Builder.Default
        private String analysisType = "downstream";
        
        @JsonPropertyDescription("分析深度。默认: 3")
        @Builder.Default
        private int maxDepth = 3;
    }
}
