package io.leavesfly.jimi.cli.config;

import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI 配置加载器
 * <p>
 * 配置优先级（高到低）：
 * 1. 环境变量
 * 2. 工作目录下 .jimi/config.yaml
 * 3. 用户主目录下 ~/.jimi/config.yaml
 * 4. classpath 中的默认配置
 * </p>
 */
public class CliConfig {

    private static final Logger log = LoggerFactory.getLogger(CliConfig.class);

    /** 配置文件名 */
    private static final String CONFIG_FILE = "config.yaml";

    /** Jimi 配置目录名 */
    private static final String JIMI_DIR = ".jimi";

    /** 原始配置 Map */
    private final Map<String, Object> config;

    private CliConfig(Map<String, Object> config) {
        this.config = config;
    }

    /**
     * 加载配置
     *
     * @param workDir 工作目录
     * @return 配置实例
     */
    public static CliConfig load(Path workDir) {
        Map<String, Object> merged = new LinkedHashMap<>();

        // 1. 先加载 classpath 默认配置
        loadClasspathConfig(merged);

        // 2. 加载 ~/.jimi/config.yaml
        Path homeConfig = Paths.get(System.getProperty("user.home"), JIMI_DIR, CONFIG_FILE);
        loadYamlFile(homeConfig, merged);

        // 3. 加载工作目录 .jimi/config.yaml
        if (workDir != null) {
            Path projectConfig = workDir.resolve(JIMI_DIR).resolve(CONFIG_FILE);
            loadYamlFile(projectConfig, merged);
        }

        return new CliConfig(merged);
    }

    /**
     * 构建 LLMConfig
     */
    public LLMConfig toLLMConfig() {
        Map<String, Object> llmMap = getMap("llm");

        // 从配置中提取
        String provider = getString(llmMap, "provider", "openai");
        String model = getString(llmMap, "model", "gpt-4o");
        String baseUrl = getString(llmMap, "baseUrl", null);
        double temperature = getDouble(llmMap, "temperature", 0.7);
        int maxTokens = getInt(llmMap, "maxTokens", 4096);
        int connectTimeout = getInt(llmMap, "connectTimeout", 30);
        int readTimeout = getInt(llmMap, "readTimeout", 120);

        // API Key: 环境变量优先 > 配置文件
        String apiKey = resolveApiKey(provider, getString(llmMap, "apiKey", null));

        return LLMConfig.builder()
                .provider(provider)
                .model(model)
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .build();
    }

    /**
     * 解析 API Key，优先使用环境变量
     */
    private String resolveApiKey(String provider, String configKey) {
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

    /**
     * 是否为 YOLO 模式
     */
    public boolean isYoloMode() {
        return getBoolean(config, "yoloMode", false);
    }

    /**
     * 获取最大上下文 Token 数
     */
    public int getMaxContextTokens() {
        return getInt(config, "maxContextTokens", 100000);
    }

    // ---- 内部辅助方法 ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(String key) {
        Object val = config.get(key);
        if (val instanceof Map) {
            return (Map<String, Object>) val;
        }
        return new LinkedHashMap<>();
    }

    private static String getString(Map<String, Object> map, String key, String defaultVal) {
        if (map == null) return defaultVal;
        Object val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    private static double getDouble(Map<String, Object> map, String key, double defaultVal) {
        if (map == null) return defaultVal;
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (NumberFormatException e) { /* ignore */ }
        }
        return defaultVal;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        if (map == null) return defaultVal;
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (NumberFormatException e) { /* ignore */ }
        }
        return defaultVal;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultVal) {
        if (map == null) return defaultVal;
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean((String) val);
        return defaultVal;
    }

    @SuppressWarnings("unchecked")
    private static void loadClasspathConfig(Map<String, Object> target) {
        try (InputStream is = CliConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                Yaml yaml = new Yaml();
                Map<String, Object> data = yaml.load(is);
                if (data != null) {
                    deepMerge(target, data);
                    log.debug("已加载 classpath 默认配置");
                }
            }
        } catch (Exception e) {
            log.debug("加载 classpath 默认配置失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadYamlFile(Path path, Map<String, Object> target) {
        if (!Files.exists(path)) {
            return;
        }
        try (InputStream is = Files.newInputStream(path)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(is);
            if (data != null) {
                deepMerge(target, data);
                log.info("已加载配置文件: {}", path);
            }
        } catch (IOException e) {
            log.warn("读取配置文件失败: {} - {}", path, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object sourceVal = entry.getValue();
            Object targetVal = target.get(key);

            if (sourceVal instanceof Map && targetVal instanceof Map) {
                deepMerge((Map<String, Object>) targetVal, (Map<String, Object>) sourceVal);
            } else {
                target.put(key, sourceVal);
            }
        }
    }
}
