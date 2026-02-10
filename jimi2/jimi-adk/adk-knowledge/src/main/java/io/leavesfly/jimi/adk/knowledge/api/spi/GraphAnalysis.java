package io.leavesfly.jimi.adk.knowledge.api.spi;

import reactor.core.publisher.Mono;

/**
 * 图谱分析接口
 * <p>
 * 提供代码影响分析和可视化导出能力。
 * 从 GraphService 中拆分，遵循接口隔离原则。
 * </p>
 */
public interface GraphAnalysis {

    /**
     * 分析代码变更的影响范围
     */
    Mono<GraphService.ImpactAnalysisResult> analyzeImpact(String entityId, String analysisType, int maxDepth);

    /**
     * 生成调用图 Mermaid 可视化
     */
    Mono<String> exportCallGraphToMermaid(String methodId, int maxDepth);
}
