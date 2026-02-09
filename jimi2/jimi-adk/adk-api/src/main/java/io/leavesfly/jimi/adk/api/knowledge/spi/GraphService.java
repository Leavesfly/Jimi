package io.leavesfly.jimi.adk.api.knowledge.spi;

import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.knowledge.query.GraphQuery;
import io.leavesfly.jimi.adk.api.knowledge.result.GraphResult;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 代码图谱服务接口（Layer 1a - 基础层）
 * 
 * <p>职责：
 * - 解析代码结构（AST）
 * - 构建实体关系图谱
 * - 提供结构化查询能力
 * - 影响分析和调用图查询
 * 
 * <p>架构定位：
 * - 与 RagService 平行，互不依赖
 * - 可被 MemoryService、WikiService、HybridSearchService 依赖
 * - 不依赖任何其他知识模块
 * 
 * @see RagService 平行的语义检索服务
 */
public interface GraphService {
    
    /**
     * 初始化图谱服务
     */
    Mono<Boolean> initialize(Runtime runtime);
    
    /**
     * 构建代码图谱
     */
    Mono<GraphResult> build(Path projectRoot);
    
    /**
     * 查询代码图谱
     */
    Mono<GraphResult> query(GraphQuery query);
    
    /**
     * 保存图谱到磁盘
     */
    Mono<Boolean> save();
    
    /**
     * 加载图谱
     */
    Mono<Boolean> load(Path storagePath);
    
    /**
     * 检查图谱是否已初始化
     */
    boolean isInitialized();
    
    /**
     * 检查图谱功能是否启用
     */
    boolean isEnabled();
    
    // ==================== 影响分析 ====================
    
    /**
     * 分析代码变更的影响范围
     */
    Mono<ImpactAnalysisResult> analyzeImpact(String entityId, String analysisType, int maxDepth);
    
    // ==================== 调用图查询 ====================
    
    /**
     * 查找调用者
     */
    Mono<List<EntityInfo>> findCallers(String methodId, int maxDepth);
    
    /**
     * 查找被调用者
     */
    Mono<List<EntityInfo>> findCallees(String methodId, int maxDepth);
    
    /**
     * 查找调用链
     */
    Mono<List<CallChainInfo>> findCallChains(String sourceMethodId, String targetMethodId, int maxDepth);
    
    /**
     * 生成调用图 Mermaid 可视化
     */
    Mono<String> exportCallGraphToMermaid(String methodId, int maxDepth);
    
    // ==================== 内部数据结构 ====================
    
    /**
     * 影响分析结果
     */
    interface ImpactAnalysisResult {
        boolean isSuccess();
        String getErrorMessage();
        String getTargetEntityId();
        String getTargetEntityName();
        String getAnalysisType();
        int getMaxDepth();
        List<EntityInfo> getDownstreamEntities();
        List<EntityInfo> getUpstreamEntities();
        Map<String, Integer> getDownstreamByType();
        Map<String, Integer> getUpstreamByType();
        int getTotalImpactedEntities();
    }
    
    /**
     * 实体信息
     */
    interface EntityInfo {
        String getId();
        String getName();
        String getType();
        String getFilePath();
        int getStartLine();
    }
    
    /**
     * 调用链信息
     */
    interface CallChainInfo {
        String getPathString();
        int getDepth();
        List<EntityInfo> getNodes();
    }
}
