package io.leavesfly.jimi.adk.knowledge;

import io.leavesfly.jimi.adk.knowledge.graph.GraphConfig;
import io.leavesfly.jimi.adk.knowledge.memory.MemoryConfig;
import io.leavesfly.jimi.adk.knowledge.rag.VectorIndexConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识系统统一配置
 * 
 * <p>聚合所有子系统的配置：
 * - GraphConfig: 代码图谱配置
 * - VectorIndexConfig: 向量索引/RAG 配置
 * - MemoryConfig: 长期记忆配置
 * - Wiki 配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeConfig {
    
    /**
     * 知识系统总开关
     */
    @Builder.Default
    private boolean enabled = true;
    
    /**
     * 代码图谱配置
     */
    @Builder.Default
    private GraphConfig graphConfig = GraphConfig.builder().build();
    
    /**
     * 向量索引/RAG 配置
     */
    @Builder.Default
    private VectorIndexConfig vectorIndexConfig = VectorIndexConfig.builder().build();
    
    /**
     * 长期记忆配置
     */
    @Builder.Default
    private MemoryConfig memoryConfig = MemoryConfig.builder().build();
    
    /**
     * Wiki 功能是否启用
     */
    @Builder.Default
    private boolean wikiEnabled = true;
    
    /**
     * 是否启用混合搜索
     */
    @Builder.Default
    private boolean hybridSearchEnabled = true;
    
    /**
     * 创建默认配置
     */
    public static KnowledgeConfig defaults() {
        return KnowledgeConfig.builder().build();
    }
    
    /**
     * 创建最小配置（仅图谱）
     */
    public static KnowledgeConfig graphOnly() {
        return KnowledgeConfig.builder()
                .vectorIndexConfig(VectorIndexConfig.builder().enabled(false).build())
                .memoryConfig(MemoryConfig.builder().longTermEnabled(false).build())
                .wikiEnabled(false)
                .hybridSearchEnabled(false)
                .build();
    }
}
