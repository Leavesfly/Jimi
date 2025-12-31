package io.leavesfly.jimi.knowledge;

import io.leavesfly.jimi.core.engine.runtime.Runtime;
import io.leavesfly.jimi.knowledge.domain.query.*;
import io.leavesfly.jimi.knowledge.domain.result.*;
import reactor.core.publisher.Mono;


/**
 * 知识服务统一门面
 *
 * <p>职责：
 * - 提供代码图谱查询能力（GraphService）
 * - 提供长期记忆管理能力（MemoryService）
 * - 提供向量语义检索能力（RagService）
 * - 提供混合搜索能力（HybridSearchService）
 * - 提供智能文档生成能力（WikiService）
 *
 * <p>设计原则：
 * - 外部模块仅依赖此接口，不感知内部实现
 * - 所有操作均返回 Mono，支持响应式编程
 * - 使用领域模型（domain）封装请求和响应
 * - 隐藏 graph/memory/rag/wiki 内部模块
 *
 * <p>架构定位：
 * - 门面层（Facade Layer）
 * - 协调和委托给内部 SPI 服务
 * - 提供统一的生命周期管理
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
     * <p>使用示例：
     * <pre>
     * UnifiedKnowledgeQuery query = UnifiedKnowledgeQuery.builder()
     *     .keyword("authentication logic")
     *     .scope(UnifiedKnowledgeQuery.SearchScope.all())
     *     .sortStrategy(UnifiedKnowledgeQuery.SortStrategy.RELEVANCE)
     *     .build();
     *
     * UnifiedKnowledgeResult result = knowledgeService.unifiedSearch(query).block();
     * </pre>
     *
     * @param query 统一搜索请求
     * @return 整合后的搜索结果
     */
    Mono<UnifiedKnowledgeResult> unifiedSearch(UnifiedKnowledgeQuery query);
}
