package io.leavesfly.jimi.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.leavesfly.jimi.exception.ConfigException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 配置加载服务
 * 负责从配置文件加载、保存和管理 Jimi 配置
 */
@Slf4j
@Service
public class ConfigLoader {

    private static final String RESOURCE_CONFIG_PATH = ".jimi/config.json";

    private final ObjectMapper objectMapper;

    public ConfigLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 获取配置文件路径
     */
    public Path getConfigFilePath() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, RESOURCE_CONFIG_PATH);
    }

    /**
     * 加载配置
     * 配置优先级：自定义配置文件 > 默认配置文件 > 内置默认配置
     */
    public JimiConfig loadConfig(Path customConfigFile) {
        Path configFile = customConfigFile != null ? customConfigFile : getConfigFilePath();

        JimiConfig config;
        if (Files.exists(configFile)) {
            log.debug("Loading config from file: {}", configFile);
            try {
                // 使用 Files.newInputStream 替代 toFile(),兼容 JAR 包模式
                config = objectMapper.readValue(Files.newInputStream(configFile), JimiConfig.class);
            } catch (IOException e) {
                throw new ConfigException("Failed to load config from file: " + configFile, e);
            }
        } else {
            log.debug("No config file found, creating default config");
            config = getDefaultConfig();
            try {
//                saveConfig(config, configFile);
            } catch (ConfigException e) {
                log.warn("Failed to save default config: {}", e.getMessage());
            }
        }

        // 验证配置
        try {
            config.validate();
        } catch (IllegalStateException e) {
            throw new ConfigException("Invalid configuration: " + e.getMessage(), e);
        }

        return config;
    }

    /**
     * 保存配置
     */
    public void saveConfig(JimiConfig config, Path configFile) {
        try {
            // 确保目录存在
            Files.createDirectories(configFile.getParent());

            // 使用 Files.newOutputStream 替代 toFile(),兼容 JAR 包模式
            objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(Files.newOutputStream(configFile), config);

            log.info("Config saved to: {}", configFile);
        } catch (IOException e) {
            throw new ConfigException("Failed to save config to file: " + configFile, e);
        }
    }

    /**
     * 获取默认内置配置
     * 从 resources/.jimi/config.json 加载
     */
    public JimiConfig getDefaultConfig() {
        try {
            URL resource = getClass().getClassLoader().getResource(RESOURCE_CONFIG_PATH);

            if (resource != null) {
                log.debug("Loading default config from classpath: {}", RESOURCE_CONFIG_PATH);
                return objectMapper.readValue(resource, JimiConfig.class);
            }
        } catch (IOException e) {
            log.warn("Failed to load default config from classpath: {}", e.getMessage());
        }

        throw new ConfigException("Failed to load default config from classpath: " + RESOURCE_CONFIG_PATH);

    }


}
