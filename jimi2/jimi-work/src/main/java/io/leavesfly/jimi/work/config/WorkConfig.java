package io.leavesfly.jimi.work.config;

import io.leavesfly.jimi.adk.api.llm.LLMConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Work 配置加载器
 * <p>
 * 配置优先级（高到低）：
 * 1. 环境变量
 * 2. 工作目录下 .jimi/config.yaml
 * 3. 用户主目录下 ~/.jimi/config.yaml
 * 4. classpath 中的默认配置
 * </p>
 */
public class WorkConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkConfig.class);
    private static final String CONFIG_FILE = "config.yaml";
    private static final String JIMI_DIR = ".jimi";

    private final Map<String, Object> config;

    private WorkConfig(Map<String, Object> config) {
        this.config = config;
    }

    /**
     * 加载配置
     */
    public static WorkConfig load(Path workDir) {
        Map<String, Object> merged = new LinkedHashMap<>();

        // 1. classpath 默认配置
        loadClasspath(merged);
        // 2. ~/.jimi/config.yaml
        loadYaml(Paths.get(System.getProperty("user.home"), JIMI_DIR, CONFIG_FILE), merged);
        // 3. 工作目录 .jimi/config.yaml
        if (workDir != null) {
            loadYaml(workDir.resolve(JIMI_DIR).resolve(CONFIG_FILE), merged);
        }

        return new WorkConfig(merged);
    }

    /**
     * 构建 LLMConfig
     */
    public LLMConfig toLLMConfig() {
        Map<String, Object> llm = getMap("llm");
        String provider = str(llm, "provider", "openai");
        String model = str(llm, "model", "gpt-4o");
        String baseUrl = str(llm, "baseUrl", null);
        double temperature = dbl(llm, "temperature", 0.7);
        int maxTokens = num(llm, "maxTokens", 4096);
        int connectTimeout = num(llm, "connectTimeout", 30);
        int readTimeout = num(llm, "readTimeout", 120);
        String apiKey = resolveApiKey(provider, str(llm, "apiKey", null));

        return LLMConfig.builder()
                .provider(provider).model(model).apiKey(apiKey).baseUrl(baseUrl)
                .temperature(temperature).maxTokens(maxTokens)
                .connectTimeout(connectTimeout).readTimeout(readTimeout)
                .build();
    }

    public boolean isYoloMode() {
        return bool(config, "yoloMode", false);
    }

    public int getMaxContextTokens() {
        return num(config, "maxContextTokens", 100000);
    }

    /**
     * 解析 API Key，优先使用环境变量
     */
    private String resolveApiKey(String provider, String configKey) {
        // JIMI_API_KEY
        String key = System.getenv("JIMI_API_KEY");
        if (key != null && !key.isEmpty()) return key;

        // {PROVIDER}_API_KEY
        String envName = provider.toUpperCase().replace("-", "_") + "_API_KEY";
        key = System.getenv(envName);
        if (key != null && !key.isEmpty()) return key;

        // OPENAI_API_KEY 后备
        if (!"openai".equalsIgnoreCase(provider)) {
            key = System.getenv("OPENAI_API_KEY");
            if (key != null && !key.isEmpty()) return key;
        }

        // 配置文件中 ${ENV} 语法
        if (configKey != null && configKey.startsWith("${") && configKey.endsWith("}")) {
            key = System.getenv(configKey.substring(2, configKey.length() - 1));
            if (key != null && !key.isEmpty()) return key;
        }

        return configKey;
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(String key) {
        Object v = config.get(key);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    private static String str(Map<String, Object> m, String k, String def) {
        if (m == null) return def;
        Object v = m.get(k);
        return v != null ? v.toString() : def;
    }

    private static double dbl(Map<String, Object> m, String k, double def) {
        if (m == null) return def;
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) { try { return Double.parseDouble((String) v); } catch (Exception e) { /* */ } }
        return def;
    }

    private static int num(Map<String, Object> m, String k, int def) {
        if (m == null) return def;
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) { try { return Integer.parseInt((String) v); } catch (Exception e) { /* */ } }
        return def;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        if (m == null) return def;
        Object v = m.get(k);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return def;
    }

    @SuppressWarnings("unchecked")
    private static void loadClasspath(Map<String, Object> target) {
        try (InputStream is = WorkConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                Map<String, Object> data = new Yaml().load(is);
                if (data != null) deepMerge(target, data);
            }
        } catch (Exception e) {
            log.debug("加载 classpath 默认配置失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadYaml(Path path, Map<String, Object> target) {
        if (!Files.exists(path)) return;
        try (InputStream is = Files.newInputStream(path)) {
            Map<String, Object> data = new Yaml().load(is);
            if (data != null) {
                deepMerge(target, data);
                log.info("已加载配置: {}", path);
            }
        } catch (Exception e) {
            log.warn("读取配置失败: {} - {}", path, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (var entry : source.entrySet()) {
            Object sv = entry.getValue(), tv = target.get(entry.getKey());
            if (sv instanceof Map && tv instanceof Map) {
                deepMerge((Map<String, Object>) tv, (Map<String, Object>) sv);
            } else {
                target.put(entry.getKey(), sv);
            }
        }
    }
}
