package io.leavesfly.jimi.adk.config;

import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jimi 统一配置根对象
 * <p>
 * 聚合 LLM 配置、运行时配置、知识系统配置等。
 * 各前端模块（CLI、Web、Desktop）共享此配置基础类，
 * 并可通过继承或委托添加前端特有的配置。
 * </p>
 *
 * @author Jimi2 Team
 */
public class JimiConfig {

    private static final Logger log = LoggerFactory.getLogger(JimiConfig.class);

    /** 原始配置 Map（向后兼容） */
    private final Map<String, Object> config;

    /** 强类型 LLM 配置 */
    private final LlmProperties llmProperties;

    /** 强类型运行时配置 */
    private final RuntimeProperties runtimeProperties;

    protected JimiConfig(Map<String, Object> config) {
        this.config = config;
        this.llmProperties = parseLlmProperties(config);
        this.runtimeProperties = parseRuntimeProperties(config);
    }

    /**
     * 加载配置
     *
     * @param workDir 工作目录
     * @return 配置实例
     */
    public static JimiConfig load(Path workDir) {
        Map<String, Object> merged = ConfigLoader.load(workDir);
        return new JimiConfig(merged);
    }

    /**
     * 获取原始配置 Map
     */
    public Map<String, Object> getRawConfig() {
        return config;
    }

    /**
     * 获取强类型 LLM 配置
     */
    public LlmProperties getLlmProperties() {
        return llmProperties;
    }

    /**
     * 获取强类型运行时配置
     */
    public RuntimeProperties getRuntimeProperties() {
        return runtimeProperties;
    }

    // ==================== LLM 配置 ====================

    /**
     * 构建 LLMConfig（基于强类型属性）
     */
    public LLMConfig toLLMConfig() {
        // API Key: 环境变量优先 > 配置文件
        String apiKey = resolveApiKey(llmProperties.getProvider(), llmProperties.getApiKey());

        return LLMConfig.builder()
                .provider(llmProperties.getProvider())
                .model(llmProperties.getModel())
                .apiKey(apiKey)
                .baseUrl(llmProperties.getBaseUrl())
                .temperature(llmProperties.getTemperature())
                .maxTokens(llmProperties.getMaxTokens())
                .connectTimeout(llmProperties.getConnectTimeout())
                .readTimeout(llmProperties.getReadTimeout())
                .build();
    }

    // ==================== 运行时配置 ====================

    /**
     * 是否为 YOLO 模式
     */
    public boolean isYoloMode() {
        return runtimeProperties.isYoloMode();
    }

    /**
     * 获取最大上下文 Token 数
     */
    public int getMaxContextTokens() {
        return runtimeProperties.getMaxContextTokens();
    }

    // ==================== API Key 解析 ====================

    /**
     * 解析 API Key，优先使用环境变量
     */
    protected String resolveApiKey(String provider, String configKey) {
        // 1. 通用环境变量: JIMI_API_KEY
        String key = System.getenv("JIMI_API_KEY");
        if (key != null && !key.isEmpty()) {
            log.debug("使用环境变量 JIMI_API_KEY");
            return key;
        }

        // 2. Provider 专用环境变量: OPENAI_API_KEY, DEEPSEEK_API_KEY 等
        String providerEnvKey = provider.toUpperCase().replace("-", "_") + "_API_KEY";
        key = System.getenv(providerEnvKey);
        if (key != null && !key.isEmpty()) {
            log.debug("使用环境变量 {}", providerEnvKey);
            return key;
        }

        // 3. 通用 OPENAI_API_KEY（兼容很多工具的习惯）
        if (!"openai".equalsIgnoreCase(provider)) {
            key = System.getenv("OPENAI_API_KEY");
            if (key != null && !key.isEmpty()) {
                log.debug("使用环境变量 OPENAI_API_KEY 作为后备");
                return key;
            }
        }

        // 4. 配置文件中的值
        if (configKey != null && !configKey.isEmpty()) {
            // 如果以 ${...} 包裹，尝试解析环境变量引用
            if (configKey.startsWith("${") && configKey.endsWith("}")) {
                String envName = configKey.substring(2, configKey.length() - 1);
                key = System.getenv(envName);
                if (key != null && !key.isEmpty()) {
                    return key;
                }
            }
            return configKey;
        }

        return null;
    }

    // ==================== 辅助方法 ====================

    @SuppressWarnings("unchecked")
    protected Map<String, Object> getMap(String key) {
        Object val = config.get(key);
        if (val instanceof Map) {
            return (Map<String, Object>) val;
        }
        return new LinkedHashMap<>();
    }

    protected static String getString(Map<String, Object> map, String key, String defaultVal) {
        if (map == null) return defaultVal;
        Object val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    protected static double getDouble(Map<String, Object> map, String key, double defaultVal) {
        if (map == null) return defaultVal;
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try {
                return Double.parseDouble((String) val);
            } catch (NumberFormatException e) { /* ignore */ }
        }
        return defaultVal;
    }

    protected static int getInt(Map<String, Object> map, String key, int defaultVal) {
        if (map == null) return defaultVal;
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (NumberFormatException e) { /* ignore */ }
        }
        return defaultVal;
    }

    protected static boolean getBoolean(Map<String, Object> map, String key, boolean defaultVal) {
        if (map == null) return defaultVal;
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean((String) val);
        return defaultVal;
    }

    // ==================== 强类型配置解析 ====================

    /**
     * 从原始配置 Map 解析 LlmProperties
     */
    @SuppressWarnings("unchecked")
    private static LlmProperties parseLlmProperties(Map<String, Object> config) {
        Object llmObj = config.get("llm");
        Map<String, Object> llmMap = (llmObj instanceof Map) ? (Map<String, Object>) llmObj : new LinkedHashMap<>();

        return LlmProperties.builder()
                .provider(getString(llmMap, "provider", "openai"))
                .model(getString(llmMap, "model", "gpt-4o"))
                .apiKey(getString(llmMap, "apiKey", null))
                .baseUrl(getString(llmMap, "baseUrl", null))
                .temperature(getDouble(llmMap, "temperature", 0.7))
                .maxTokens(getInt(llmMap, "maxTokens", 4096))
                .connectTimeout(getInt(llmMap, "connectTimeout", 30))
                .readTimeout(getInt(llmMap, "readTimeout", 120))
                .build();
    }

    /**
     * 从原始配置 Map 解析 RuntimeProperties
     */
    private static RuntimeProperties parseRuntimeProperties(Map<String, Object> config) {
        return RuntimeProperties.builder()
                .yoloMode(getBoolean(config, "yoloMode", false))
                .maxContextTokens(getInt(config, "maxContextTokens", 100000))
                .build();
    }
}
