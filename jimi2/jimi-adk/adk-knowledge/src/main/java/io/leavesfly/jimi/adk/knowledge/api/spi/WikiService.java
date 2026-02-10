package io.leavesfly.jimi.adk.knowledge.api.spi;

import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.knowledge.api.query.WikiQuery;
import io.leavesfly.jimi.adk.knowledge.api.result.WikiResult;
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
 */
public interface WikiService {
    
    /**
     * 初始化 Wiki 服务
     */
    Mono<Boolean> initialize(Runtime runtime);
    
    /**
     * 生成 Wiki 文档
     */
    Mono<WikiResult> generate(WikiQuery query);
    
    /**
     * 检测代码变更并更新 Wiki
     */
    Mono<WikiResult> detectAndUpdate(Path projectRoot);
    
    /**
     * 搜索 Wiki 内容
     */
    Mono<WikiResult> search(WikiQuery query);
    
    /**
     * 验证 Wiki 文档质量
     */
    Mono<WikiResult> validate(WikiQuery query);
    
    /**
     * 检查 Wiki 功能是否启用
     */
    boolean isEnabled();
}
