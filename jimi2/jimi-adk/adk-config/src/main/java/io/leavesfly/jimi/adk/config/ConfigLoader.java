package io.leavesfly.jimi.adk.config;

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
 * 统一配置加载器
 * <p>
 * 配置优先级（高到低）：
 * 1. 环境变量
 * 2. 工作目录下 .jimi/config.yaml
 * 3. 用户主目录下 ~/.jimi/config.yaml
 * 4. classpath 中的默认配置（config.yaml）
 * </p>
 *
 * @author Jimi2 Team
 */
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private static final String CONFIG_FILE = "config.yaml";
    private static final String JIMI_DIR = ".jimi";

    private ConfigLoader() {
        // utility class
    }

    /**
     * 加载并合并所有层级的配置
     *
     * @param workDir 工作目录（可为 null）
     * @return 合并后的配置 Map
     */
    public static Map<String, Object> load(Path workDir) {
        Map<String, Object> merged = new LinkedHashMap<>();

        // 1. classpath 默认配置
        loadClasspath(merged);
        // 2. ~/.jimi/config.yaml
        loadYaml(Paths.get(System.getProperty("user.home"), JIMI_DIR, CONFIG_FILE), merged);
        // 3. 工作目录 .jimi/config.yaml
        if (workDir != null) {
            loadYaml(workDir.resolve(JIMI_DIR).resolve(CONFIG_FILE), merged);
        }

        return merged;
    }

    /**
     * 加载 classpath 中的默认配置
     */
    @SuppressWarnings("unchecked")
    private static void loadClasspath(Map<String, Object> target) {
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
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

    /**
     * 加载指定路径的 YAML 文件
     */
    @SuppressWarnings("unchecked")
    private static void loadYaml(Path path, Map<String, Object> target) {
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
        } catch (Exception e) {
            log.warn("读取配置文件失败: {} - {}", path, e.getMessage());
        }
    }

    /**
     * 深度合并两个 Map，source 覆盖 target
     */
    @SuppressWarnings("unchecked")
    public static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
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
