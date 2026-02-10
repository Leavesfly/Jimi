package io.leavesfly.jimi.adk.knowledge.api.spi;

import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.knowledge.api.query.MemoryQuery;
import io.leavesfly.jimi.adk.knowledge.api.result.MemoryResult;
import reactor.core.publisher.Mono;

/**
 * 长期记忆服务接口（Layer 2 - 核心层）
 * 
 * <p>职责：
 * - 管理用户偏好、项目洞察、错误模式等长期记忆
 * - 使用 RagService 进行语义检索
 * - 使用 GraphService 关联代码上下文
 * - 从会话历史中提取知识
 * 
 * <p>架构定位：
 * - 依赖 GraphService 和 RagService（Layer 1）
 * - 可被 WikiService 依赖
 * - 不依赖 WikiService
 * 
 * @see GraphService 用于代码上下文关联
 * @see RagService 用于语义检索
 */
public interface MemoryService {
    
    /**
     * 初始化记忆服务
     */
    Mono<Boolean> initialize(Runtime runtime);
    
    /**
     * 查询长期记忆
     */
    Mono<MemoryResult> query(MemoryQuery query);
    
    /**
     * 添加记忆
     */
    Mono<MemoryResult> add(MemoryQuery query);
    
    /**
     * 从会话中提取并保存记忆
     */
    Mono<MemoryResult> extractFromSession(Runtime runtime);
    
    /**
     * 删除记忆
     */
    Mono<MemoryResult> delete(String memoryId);
    
    /**
     * 检查记忆功能是否启用
     */
    boolean isEnabled();
}
