package io.leavesfly.jimi.adk.knowledge.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 向量索引配置
 * <p>
 * 控制 RAG 子系统的分块、嵌入、检索参数。
 * 后续会被 KnowledgeConfig 统一引用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorIndexConfig {

    /**
     * 是否启用向量索引
     */
    @Builder.Default
    private boolean enabled = false;

    /**
     * 索引存储路径（相对于工作目录）
     */
    @Builder.Default
    private String indexPath = ".jimi/index";

    /**
     * 分块大小（行数）
     */
    @Builder.Default
    private int chunkSize = 50;

    /**
     * 分块重叠大小（行数）
     */
    @Builder.Default
    private int chunkOverlap = 5;

    /**
     * TopK 检索数量
     */
    @Builder.Default
    private int topK = 5;

    /**
     * 嵌入提供者类型（local, openai, dashscope等）
     */
    @Builder.Default
    private String embeddingProvider = "local";

    /**
     * 嵌入模型名称
     */
    @Builder.Default
    private String embeddingModel = "all-minilm-l6-v2";

    /**
     * 向量维度（由嵌入模型决定）
     */
    @Builder.Default
    private int embeddingDimension = 384;

    /**
     * 存储类型（memory, file等）
     */
    @Builder.Default
    private String storageType = "file";

    /**
     * 是否在启动时自动加载索引
     */
    @Builder.Default
    private boolean autoLoad = true;

    /**
     * 支持的文件扩展名（逗号分隔）
     */
    @Builder.Default
    private String fileExtensions = ".java,.kt,.py,.js,.ts,.go,.rs";

    /**
     * 排除的路径模式（glob模式，逗号分隔）
     */
    @Builder.Default
    private String excludePatterns = "**/target/**,**/build/**,**/node_modules/**,**/.git/**";
}
