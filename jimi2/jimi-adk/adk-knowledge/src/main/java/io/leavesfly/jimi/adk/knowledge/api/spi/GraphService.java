package io.leavesfly.jimi.adk.knowledge.api.spi;

import java.util.List;
import java.util.Map;

/**
 * 代码图谱服务聚合接口（Layer 1a - 基础层）
 * 
 * <p>聚合了三个细粒度子接口，提供完整的图谱服务能力：
 * <ul>
 *   <li>{@link GraphLifecycle} - 生命周期管理（初始化、构建、保存、加载）</li>
 *   <li>{@link GraphQueryService} - 结构化查询与调用关系分析</li>
 *   <li>{@link GraphAnalysis} - 影响分析与可视化导出</li>
 * </ul>
 * 
 * <p>调用方可以根据需要仅依赖子接口（接口隔离原则），
 * 也可以依赖本聚合接口获得全部能力。
 * 
 * <p>架构定位：
 * - 与 RagService 平行，互不依赖
 * - 可被 MemoryService、WikiService、HybridSearchService 依赖
 * - 不依赖任何其他知识模块
 * 
 * @see GraphLifecycle 生命周期管理子接口
 * @see GraphQueryService 查询子接口
 * @see GraphAnalysis 分析子接口
 * @see RagService 平行的语义检索服务
 */
public interface GraphService extends GraphLifecycle, GraphQueryService, GraphAnalysis {
    
    // 所有方法声明继承自三个子接口，此处仅保留数据结构定义
    
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
