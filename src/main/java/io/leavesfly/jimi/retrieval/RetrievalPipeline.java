package io.leavesfly.jimi.retrieval;

import io.leavesfly.jimi.engine.context.Context;
import io.leavesfly.jimi.engine.runtime.Runtime;
import io.leavesfly.jimi.llm.message.ContentPart;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 检索增强管线接口
 * <p>
 * 职责：
 * - 分析当前上下文，生成检索查询
 * - 调用向量存储进行相似度搜索
 * - 将检索结果格式化并注入到上下文
 * - 控制检索时机和注入策略
 * <p>
 * 执行流程：
 * 1. 从Context提取最近消息，生成检索Query
 * 2. 使用EmbeddingProvider生成查询向量
 * 3. 调用VectorStore进行TopK搜索
 * 4. 格式化检索结果为ContentPart
 * 5. 注入到Context（或返回给调用者注入）
 */
public interface RetrievalPipeline {

    /**
     * 执行检索并注入上下文
     * <p>
     * 从Context分析用户意图，检索相关代码片段，并格式化注入
     *
     * @param context 当前上下文
     * @param runtime 运行时环境
     * @return 检索到的片段数量（异步）
     */
    Mono<Integer> retrieveAndInject(Context context, Runtime runtime);

    /**
     * 仅执行检索（不注入）
     * <p>
     * 用于预览、调试或手动控制注入
     *
     * @param query   检索查询文本
     * @param topK    返回结果数量
     * @param runtime 运行时环境
     * @return 检索结果及格式化内容（异步）
     */
    Mono<RetrievalResult> retrieve(String query, int topK, Runtime runtime);

    /**
     * 将检索结果格式化为ContentPart列表
     * <p>
     * 用于注入到Context.getHistory()
     *
     * @param result 检索结果
     * @return 格式化后的内容部分列表
     */
    List<ContentPart> formatAsContentParts(RetrievalResult result);

    /**
     * 检索结果数据模型
     */
    @lombok.Data
    @lombok.Builder
    class RetrievalResult {
        private String query;                           // 原始查询
        private List<VectorStore.SearchResult> results; // 检索结果
        private int totalRetrieved;                     // 检索到的片段数
        private long elapsedMs;                         // 检索耗时（毫秒）
        private String formattedContent;                // 格式化后的文本（用于日志或预览）
    }
}
