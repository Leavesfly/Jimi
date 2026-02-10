package io.leavesfly.jimi.adk.knowledge.api.spi;

import io.leavesfly.jimi.adk.knowledge.api.query.GraphQuery;
import io.leavesfly.jimi.adk.knowledge.api.result.GraphResult;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 图谱查询接口
 * <p>
 * 提供代码图谱的结构化查询和调用关系分析能力。
 * 从 GraphService 中拆分，遵循接口隔离原则。
 * </p>
 */
public interface GraphQueryService {

    /**
     * 查询代码图谱
     */
    Mono<GraphResult> query(GraphQuery query);

    /**
     * 查找调用者
     */
    Mono<List<GraphService.EntityInfo>> findCallers(String methodId, int maxDepth);

    /**
     * 查找被调用者
     */
    Mono<List<GraphService.EntityInfo>> findCallees(String methodId, int maxDepth);

    /**
     * 查找调用链
     */
    Mono<List<GraphService.CallChainInfo>> findCallChains(String sourceMethodId, String targetMethodId, int maxDepth);
}
