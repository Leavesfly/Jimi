package io.leavesfly.jimi.knowledge.spi;

import io.leavesfly.jimi.core.engine.runtime.Runtime;
import io.leavesfly.jimi.knowledge.domain.query.WikiQuery;
import io.leavesfly.jimi.knowledge.domain.result.WikiResult;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

/**
 * 智能文档服务接口（Layer 3 - 应用层）
 * 
 * <p>职责：
 * - 生成项目文档
 * - 检测代码变更并更新文档
 * - 生成架构图、依赖图等可视化内容
 * 
 * <p>架构定位：
 * - 可依赖所有下层模块：GraphService、RagService、MemoryService、HybridSearchService
 * - 最上层的应用服务
 * - 不被其他知识模块依赖
 * 
 * <p>能力集成：
 * - 使用 GraphService 分析代码结构
 * - 使用 RagService 搜索相关代码
 * - 使用 MemoryService 提取历史知识
 * - 使用 HybridSearchService 执行混合搜索
 */
public interface WikiService {
    
    /**
     * 初始化 Wiki 服务
     * 
     * @param runtime 运行时环境
     * @return 初始化结果
     */
    Mono<Boolean> initialize(Runtime runtime);
    
    /**
     * 生成 Wiki 文档
     * 
     * @param query Wiki 生成请求
     * @return 生成结果
     */
    Mono<WikiResult> generate(WikiQuery query);
    
    /**
     * 检测代码变更并更新 Wiki
     * 
     * @param projectRoot 项目根目录
     * @return 变更检测结果
     */
    Mono<WikiResult> detectAndUpdate(Path projectRoot);
    
    /**
     * 搜索 Wiki 内容
     * 
     * @param query Wiki 搜索请求
     * @return 搜索结果
     */
    Mono<WikiResult> search(WikiQuery query);
    
    /**
     * 验证 Wiki 文档质量
     * 
     * @param query Wiki 验证请求
     * @return 验证结果
     */
    Mono<WikiResult> validate(WikiQuery query);
    
    /**
     * 检查 Wiki 功能是否启用
     * 
     * @return 是否启用
     */
    boolean isEnabled();
}
