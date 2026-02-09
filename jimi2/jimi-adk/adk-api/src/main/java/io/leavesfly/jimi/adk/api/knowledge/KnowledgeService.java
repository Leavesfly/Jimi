package io.leavesfly.jimi.adk.api.knowledge;

import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.knowledge.query.UnifiedKnowledgeQuery;
import io.leavesfly.jimi.adk.api.knowledge.result.UnifiedKnowledgeResult;
import reactor.core.publisher.Mono;

/**
 * 知识服务统一门面
 */
public interface KnowledgeService {

    Mono<Boolean> initialize(Runtime runtime);

    // ==================== 统一知识搜索 ====================

    /**
     * 统一知识搜索
     *
     * <p>整合 Graph、Memory、Retrieval、Wiki 四个模块的搜索能力，
     * 提供一站式的知识检索接口。
     *
     * <p>搜索流程：
     * 1. 根据 SearchScope 配置决定搜索范围
     * 2. 并发调用各个模块的搜索接口
     * 3. 合并结果，根据 SortStrategy 进行排序
     * 4. 识别跨模块关联（如代码实体对应的记忆、Wiki文档等）
     *
     * @param query 统一搜索请求
     * @return 整合后的搜索结果
     */
    Mono<UnifiedKnowledgeResult> unifiedSearch(UnifiedKnowledgeQuery query);
}
