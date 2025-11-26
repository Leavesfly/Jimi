package io.leavesfly.jimi.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 向量索引配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorIndexConfig {

    /**
     * 是否启用向量索引
     */
    @JsonProperty("enabled")
    @Builder.Default
    private boolean enabled = false;

    /**
     * 索引存储路径（相对于工作目录）
     */
    @JsonProperty("index_path")
    @Builder.Default
    private String indexPath = ".jimi/index";

    /**
     * 分块大小（行数）
     */
    @JsonProperty("chunk_size")
    @Builder.Default
    private int chunkSize = 50;

    /**
     * 分块重叠大小（行数）
     */
    @JsonProperty("chunk_overlap")
    @Builder.Default
    private int chunkOverlap = 5;

    /**
     * TopK 检索数量
     */
    @JsonProperty("top_k")
    @Builder.Default
    private int topK = 5;

    /**
     * 嵌入提供者类型（local, openai, dashscope等）
     */
    @JsonProperty("embedding_provider")
    @Builder.Default
    private String embeddingProvider = "local";

    /**
     * 嵌入模型名称
     */
    @JsonProperty("embedding_model")
    @Builder.Default
    private String embeddingModel = "all-minilm-l6-v2";

    /**
     * 向量维度（由嵌入模型决定）
     */
    @JsonProperty("embedding_dimension")
    @Builder.Default
    private int embeddingDimension = 384;

    /**
     * 存储类型（memory, file等）
     */
    @JsonProperty("storage_type")
    @Builder.Default
    private String storageType = "file";

    /**
     * 是否在启动时自动加载索引
     */
    @JsonProperty("auto_load")
    @Builder.Default
    private boolean autoLoad = true;

    /**
     * 支持的文件扩展名（逗号分隔）
     */
    @JsonProperty("file_extensions")
    @Builder.Default
    private String fileExtensions = ".java,.kt,.py,.js,.ts,.go,.rs";

    /**
     * 排除的路径模式（glob模式，逗号分隔）
     */
    @JsonProperty("exclude_patterns")
    @Builder.Default
    private String excludePatterns = "**/target/**,**/build/**,**/node_modules/**,**/.git/**";
}
