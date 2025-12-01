package io.leavesfly.jimi.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Jimi 全局配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JimiConfig {
    
    /**
     * 默认模型名称
     */
    @JsonProperty("default_model")
    @Builder.Default
    private String defaultModel = "";
    
    /**
     * 模型配置映射
     */
    @JsonProperty("models")
    @NotNull
    @Valid
    @Builder.Default
    private Map<String, LLMModelConfig> models = new HashMap<>();
    
    /**
     * 提供商配置映射
     */
    @JsonProperty("providers")
    @NotNull
    @Valid
    @Builder.Default
    private Map<String, LLMProviderConfig> providers = new HashMap<>();
    
    /**
     * 循环控制配置
     */
    @JsonProperty("loop_control")
    @NotNull
    @Valid
    @Builder.Default
    private LoopControlConfig loopControl = new LoopControlConfig();

    /**
     * Web搜索配置
     */
    @JsonProperty("web_search")
    @Valid
    @Builder.Default
    private WebSearchConfig webSearch = new WebSearchConfig();

    /**
     * 向量索引配置
     */
    @JsonProperty("vector_index")
    @Valid
    @Builder.Default
    private VectorIndexConfig vectorIndex = new VectorIndexConfig();

    /**
     * 代码图配置
     */
    @JsonProperty("graph")
    @Valid
    @Builder.Default
    private GraphConfig graph = new GraphConfig();

    
    /**
     * 验证配置的一致性
     */
    public void validate() {
        // 验证默认模型存在
        if (!defaultModel.isEmpty() && !models.containsKey(defaultModel)) {
            throw new IllegalStateException(
                String.format("Default model '%s' not found in models", defaultModel)
            );
        }
        
        // 验证每个模型的提供商存在
        for (Map.Entry<String, LLMModelConfig> entry : models.entrySet()) {
            String modelName = entry.getKey();
            LLMModelConfig modelConfig = entry.getValue();
            if (!providers.containsKey(modelConfig.getProvider())) {
                throw new IllegalStateException(
                    String.format("Provider '%s' for model '%s' not found in providers", 
                                 modelConfig.getProvider(), modelName)
                );
            }
        }
    }
}
