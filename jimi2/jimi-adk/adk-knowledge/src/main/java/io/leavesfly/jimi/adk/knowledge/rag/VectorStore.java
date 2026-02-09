package io.leavesfly.jimi.adk.knowledge.rag;

import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;

/**
 * 向量存储接口
 * <p>
 * 职责：
 * - 存储和检索代码片段及其向量
 * - 支持相似度搜索（TopK）
 * - 管理索引生命周期（构建、更新、删除）
 * <p>
 * 实现可以是：
 * - 文件型（JSONL + 向量二进制文件）
 * - 内存型（HashMap + 线性搜索，适合小项目）
 * - 外部引擎（通过MCP调用向量数据库）
 */
public interface VectorStore {

    /**
     * 添加单个代码片段到索引
     *
     * @param chunk 代码片段
     * @return 是否成功（异步）
     */
    Mono<Boolean> add(CodeChunk chunk);

    /**
     * 批量添加代码片段到索引
     *
     * @param chunks 代码片段列表
     * @return 成功添加的数量（异步）
     */
    Mono<Integer> addBatch(List<CodeChunk> chunks);

    /**
     * 根据ID删除代码片段
     *
     * @param id 片段ID
     * @return 是否成功（异步）
     */
    Mono<Boolean> delete(String id);

    /**
     * 根据文件路径删除所有相关片段
     *
     * @param filePath 文件路径
     * @return 删除的数量（异步）
     */
    Mono<Integer> deleteByFilePath(String filePath);

    /**
     * 向量相似度搜索（TopK）
     *
     * @param queryVector 查询向量
     * @param topK        返回结果数量
     * @return 相似度最高的K个片段及其分数（异步）
     */
    Mono<List<SearchResult>> search(float[] queryVector, int topK);

    /**
     * 混合搜索（向量 + 关键词过滤）
     *
     * @param queryVector 查询向量
     * @param topK        返回结果数量
     * @param filter      过滤条件（如：language=java, symbol contains "Service"）
     * @return 相似度最高的K个片段及其分数（异步）
     */
    Mono<List<SearchResult>> search(float[] queryVector, int topK, SearchFilter filter);

    /**
     * 获取索引统计信息
     *
     * @return 统计信息（片段总数、文件数、最后更新时间等）
     */
    Mono<IndexStats> getStats();

    /**
     * 清空索引
     *
     * @return 是否成功（异步）
     */
    Mono<Boolean> clear();

    /**
     * 持久化索引到磁盘（如果支持）
     *
     * @return 是否成功（异步）
     */
    Mono<Boolean> save();

    /**
     * 从磁盘加载索引（如果支持）
     *
     * @param indexPath 索引文件路径
     * @return 是否成功（异步）
     */
    Mono<Boolean> load(Path indexPath);

    /**
     * 搜索结果（片段 + 相似度分数）
     */
    @lombok.Data
    @lombok.Builder
    class SearchResult {
        private CodeChunk chunk;
        private double score; // 相似度分数（余弦相似度、点积等）
    }

    /**
     * 搜索过滤条件
     */
    @lombok.Data
    @lombok.Builder
    class SearchFilter {
        private String language;      // 编程语言过滤
        private String filePattern;   // 文件路径模式（glob）
        private String symbolPattern; // 符号名称模式（正则）
        private Long minUpdatedAt;    // 最小更新时间
    }

    /**
     * 索引统计信息
     */
    @lombok.Data
    @lombok.Builder
    class IndexStats {
        private int totalChunks;      // 片段总数
        private int totalFiles;       // 文件总数
        private long lastUpdated;     // 最后更新时间
        private long indexSizeBytes;  // 索引大小（字节）
        private String storageType;   // 存储类型
    }
}
