package io.leavesfly.jimi.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.exception.ConfigException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * 通用 YAML 配置加载器
 *
 * 统一了 HookLoader、CustomCommandLoader 中重复的三层加载逻辑：
 * 1. 类路径 (resources/{subDir}/) - 内置示例
 * 2. 用户主目录 (~/.jimi/{subDir}/)
 * 3. 项目目录 ({project}/.jimi/{subDir}/)
 */
@Slf4j
@Service
public class YamlConfigLoader {

    @Autowired
    @Qualifier("yamlObjectMapper")
    private ObjectMapper yamlObjectMapper;

    /**
     * 从所有位置加载 YAML 配置（三层合并）
     *
     * @param subDir     子目录名（如 "hooks"、"commands"）
     * @param type       目标类型
     * @param projectDir 项目目录（可选）
     * @param <T>        配置类型
     * @return 配置列表
     */
    public <T> List<T> loadAll(String subDir, Class<T> type, Path projectDir) {
        List<T> results = new ArrayList<>();

        results.addAll(loadFromClasspath(subDir, type));
        results.addAll(loadFromUserHome(subDir, type));

        if (projectDir != null) {
            results.addAll(loadFromProject(subDir, type, projectDir));
        }

        log.info("Total {} {} configs loaded", results.size(), subDir);
        return results;
    }

    /**
     * 从类路径加载
     */
    public <T> List<T> loadFromClasspath(String subDir, Class<T> type) {
        try {
            URL resource = getClass().getClassLoader().getResource(subDir);
            if (resource == null) {
                log.debug("No {} directory found in classpath", subDir);
                return Collections.emptyList();
            }

            if (resource.getProtocol().equals("jar")) {
                log.debug("Running from JAR, skipping classpath {}", subDir);
                return Collections.emptyList();
            }

            Path directory = Paths.get(resource.toURI());
            return loadFromDirectory(directory, type, "classpath");
        } catch (Exception e) {
            log.warn("Failed to load {} from classpath", subDir, e);
            return Collections.emptyList();
        }
    }

    /**
     * 从用户主目录加载
     */
    public <T> List<T> loadFromUserHome(String subDir, Class<T> type) {
        Path directory = getUserDirectory(subDir);

        if (!Files.exists(directory)) {
            log.debug("User {} directory not found: {}", subDir, directory);
            return Collections.emptyList();
        }

        return loadFromDirectory(directory, type, "user");
    }

    /**
     * 从项目目录加载
     */
    public <T> List<T> loadFromProject(String subDir, Class<T> type, Path projectDir) {
        Path directory = getProjectDirectory(subDir, projectDir);

        if (!Files.exists(directory)) {
            log.debug("Project {} directory not found: {}", subDir, directory);
            return Collections.emptyList();
        }

        return loadFromDirectory(directory, type, "project");
    }

    /**
     * 从指定目录加载所有 YAML 文件
     */
    public <T> List<T> loadFromDirectory(Path directory, Class<T> type, String source) {
        List<T> results = new ArrayList<>();

        if (!Files.isDirectory(directory)) {
            return results;
        }

        try (Stream<Path> paths = Files.walk(directory, 1)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> {
                     String name = p.getFileName().toString();
                     return name.endsWith(".yaml") || name.endsWith(".yml");
                 })
                 .forEach(file -> {
                     try {
                         T spec = parseYamlFile(file, type);
                         if (spec != null) {
                             results.add(spec);
                             log.debug("Loaded {} config from {} ({})",
                                     type.getSimpleName(), source, file.getFileName());
                         }
                     } catch (Exception e) {
                         log.error("Failed to load config from {}: {}",
                                 file, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.error("Failed to scan {} directory: {}", source, directory, e);
        }

        log.debug("Loaded {} {} configs from {}", results.size(), type.getSimpleName(), source);
        return results;
    }

    /**
     * 解析单个 YAML 文件
     */
    public <T> T parseYamlFile(Path file, Class<T> type) {
        try {
            return yamlObjectMapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new ConfigException("Failed to parse YAML file: " + file, e);
        }
    }

    /**
     * 确保用户配置目录存在
     */
    public Path ensureUserDirectory(String subDir) {
        Path directory = getUserDirectory(subDir);
        try {
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
                log.info("Created user {} directory: {}", subDir, directory);
            }
        } catch (IOException e) {
            log.error("Failed to create user {} directory: {}", subDir, directory, e);
        }
        return directory;
    }

    /**
     * 获取用户配置目录
     */
    public Path getUserDirectory(String subDir) {
        return Paths.get(System.getProperty("user.home"), ".jimi", subDir);
    }

    /**
     * 获取项目配置目录
     */
    public Path getProjectDirectory(String subDir, Path projectDir) {
        return projectDir.resolve(".jimi").resolve(subDir);
    }
}
