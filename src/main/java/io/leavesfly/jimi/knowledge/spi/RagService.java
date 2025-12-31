package io.leavesfly.jimi.knowledge.spi;

import io.leavesfly.jimi.core.engine.runtime.Runtime;
import io.leavesfly.jimi.knowledge.domain.query.RetrievalQuery;
import io.leavesfly.jimi.knowledge.domain.result.RetrievalResult;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

/**
 * 向量检索服务接口（Layer 1b - 基础层）
 * 
 * <p>职责：
 * - 代码分块（Chunking）
 * - 向量嵌入（Embedding）
 * - 语义相似度检索
 * 
 * <p>架构定位：
 * - 与 GraphService 平行，互不依赖
 * - 可被 MemoryService、WikiService、HybridSearchService 依赖
 * - 不依赖任何其他知识模块
 * 
 * @see GraphService 平行的结构化分析服务
 */
public interface RagService {
    
    /**
     * 初始化检索服务
     * 
     * @param runtime 运行时环境
     * @return 初始化结果
     */
    Mono<Boolean> initialize(Runtime runtime);
    
    /**
     * 执行语义检索
     * 
     * @param query 检索查询请求
     * @return 检索结果
     */
    Mono<RetrievalResult> retrieve(RetrievalQuery query);
    
    /**
     * 构建代码索引
     * 
     * @param projectRoot 项目根目录
     * @return 索引结果（chunk数、文件数等）
     */
    Mono<RetrievalResult> buildIndex(Path projectRoot);
    
    /**
     * 保存索引到磁盘
     * 
     * @return 保存结果
     */
    Mono<Boolean> save();
    
    /**
     * 加载索引
     * 
     * @param indexPath 索引路径
     * @return 加载结果
     */
    Mono<Boolean> load(Path indexPath);
    
    /**
     * 获取索引统计信息
     * 
     * @return 统计信息
     */
    Mono<RetrievalResult.IndexStats> getStats();
    
    /**
     * 检查索引功能是否启用
     * 
     * @return 是否启用
     */
    boolean isEnabled();
    
    /**
     * 清空索引
     * 
     * @return 清空结果
     */
    Mono<Boolean> clear();
}
