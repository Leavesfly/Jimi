package io.leavesfly.jimi.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.jimi.config.info.GraphConfig;
import io.leavesfly.jimi.config.info.LLMProviderConfig;
import io.leavesfly.jimi.config.info.ShellUIConfig;
import io.leavesfly.jimi.config.info.VectorIndexConfig;
import io.leavesfly.jimi.core.compaction.Compaction;
import io.leavesfly.jimi.core.compaction.SimpleCompaction;
import io.leavesfly.jimi.core.engine.executor.ContextManager;
import io.leavesfly.jimi.core.engine.executor.MemoryRecorder;
import io.leavesfly.jimi.core.engine.executor.ResponseProcessor;
import io.leavesfly.jimi.exception.ConfigException;
import io.leavesfly.jimi.knowledge.graph.GraphManager;
import io.leavesfly.jimi.knowledge.rag.*;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.WireImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Jimi 应用配置类
 * 统一管理核心 Bean 的创建和配置
 */
@Slf4j
@Configuration
public class JimiConfiguration {

    /**
     * ObjectMapper Bean - JSON 序列化/反序列化
     * 全局单例,用于所有 JSON 处理
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // 注册 JavaTimeModule 以支持 Java 8 时间类型
        mapper.registerModule(new JavaTimeModule());

        // 禁用将日期写为时间戳
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 忽略未知属性（提高容错性）
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }
    
    /**
     * Wire Bean - 消息总线
     * 全局单例，用于 Engine 内部消息传递
     * 使用 share() 确保所有订阅者共享同一个流，避免重复消息
     */
    @Bean
    public Wire wire() {
        return new WireImpl();
    }
    
    /**
     * Compaction Bean - 上下文压缩策略
     * 全局单例，提供默认的 SimpleCompaction 实现
     */
    @Bean
    public Compaction compaction() {
        return new SimpleCompaction();
    }
    

    /**
     * YAML ObjectMapper Bean - YAML 序列化/反序列化
     * 用于配置文件和 Agent 规范的读取
     */
    @Bean("yamlObjectMapper")
    public ObjectMapper yamlObjectMapper() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

        // 注册 JavaTimeModule
        mapper.registerModule(new JavaTimeModule());

        // 忽略未知属性
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }

    /**
     * JimiConfig Bean - 全局配置单例
     * 在应用启动时加载配置
     */
    @Bean
    public JimiConfig jimiConfig(ObjectMapper objectMapper) {
        return ConfigLoaderHelper.loadConfig(objectMapper, null);
    }

    /**
     * GraphConfig Bean - 代码图配置
     * 从 JimiConfig 中获取
     */
    @Bean
    @Autowired
    public GraphConfig graphConfig(JimiConfig jimiConfig) {
        return jimiConfig.getGraph();
    }

    /**
     * GraphManager Bean - 代码图管理器
     * 统一管理代码图的生命周期
     */
    @Bean
    @Autowired
    public GraphManager graphManager(GraphConfig graphConfig) {
        return new GraphManager(graphConfig);
    }

    /**
     * ShellUIConfig Bean - Shell UI 配置
     * 从 JimiConfig 中获取
     */
    @Bean
    @Autowired
    public ShellUIConfig shellUIConfig(JimiConfig jimiConfig) {
        return jimiConfig.getShellUI();
    }

    /**
     * MetaToolConfig Bean - MetaTool 配置
     * 从 JimiConfig 中获取
     */
    @Bean
    @Autowired
    public io.leavesfly.jimi.config.info.MetaToolConfig metaToolConfig(JimiConfig jimiConfig) {
        return jimiConfig.getMetaTool();
    }

    /**
     * MemoryConfig Bean - 记忆模块配置
     * 从 JimiConfig 中获取
     */
    @Bean
    @Autowired
    public io.leavesfly.jimi.config.info.MemoryConfig memoryConfig(JimiConfig jimiConfig) {
        return jimiConfig.getMemory();
    }

    /**
     * VectorIndexConfig Bean - 向量索引配置
     * 从 JimiConfig 中获取
     */
    @Bean
    @Autowired
    public VectorIndexConfig vectorIndexConfig(JimiConfig jimiConfig) {
        return jimiConfig.getVectorIndex();
    }

    // ==================== 向量索引相关组件 ====================

    /**
     * 创建嵌入提供者
     * 根据 JimiConfig 中的 vector_index.enabled 配置条件性创建
     */
    @Bean
    @Autowired
    public EmbeddingProvider embeddingProvider(JimiConfig jimiConfig, ObjectMapper objectMapper) {
        VectorIndexConfig config = jimiConfig.getVectorIndex();

        // 如果未启用向量索引，返回 Mock 实现
        if (!config.isEnabled()) {
            log.debug("Vector index is disabled, returning mock embedding provider");
            return new MockEmbeddingProvider(1024, "disabled");
        }

        String providerType = config.getEmbeddingProvider();
        String embeddingModel = config.getEmbeddingModel();
        int dimension = config.getEmbeddingDimension();

        log.info("Creating EmbeddingProvider: type={}, model={}, dimension={}",
                providerType, embeddingModel, dimension);

        switch (providerType.toLowerCase()) {
            case "qwen":
                // 获取qwen提供商配置
                LLMProviderConfig qwenConfig = jimiConfig.getProviders().get("qwen");
                if (qwenConfig == null) {
                    log.error("Qwen provider not configured, falling back to mock");
                    return new MockEmbeddingProvider(dimension, "qwen-fallback");
                }
                return new QwenEmbeddingProvider(embeddingModel, dimension, qwenConfig, objectMapper);

            case "mock":
            case "local":
                return new MockEmbeddingProvider(dimension, providerType);

            default:
                log.warn("Unknown embedding provider: {}, falling back to mock", providerType);
                return new MockEmbeddingProvider(dimension, "mock");
        }
    }

    /**
     * 创建向量存储
     * 根据 JimiConfig 中的 vector_index.enabled 配置条件性创建
     */
    @Bean
    @Autowired
    public VectorStore vectorStore(JimiConfig jimiConfig, EmbeddingProvider embeddingProvider, ObjectMapper objectMapper) {
        VectorIndexConfig config = jimiConfig.getVectorIndex();

        // 如果未启用向量索引，返回空实现
        if (!config.isEnabled()) {
            log.debug("Vector index is disabled, returning empty vector store");
            return new InMemoryVectorStore(objectMapper);
        }

        String storageType = config.getStorageType();
        String indexPath = config.getIndexPath();

        log.info("Creating VectorStore: type={}, path={}", storageType, indexPath);

        VectorStore store;

        // 目前只支持内存存储，后续可扩展
        switch (storageType.toLowerCase()) {
            case "memory":
            case "file":
                InMemoryVectorStore inMemoryStore = new InMemoryVectorStore(objectMapper);
                // 设置配置的索引路径（相对路径）
                inMemoryStore.setConfiguredIndexPath(indexPath);
                store = inMemoryStore;
                break;
            default:
                log.warn("Unknown storage type: {}, falling back to in-memory", storageType);
                InMemoryVectorStore fallbackStore = new InMemoryVectorStore(objectMapper);
                fallbackStore.setConfiguredIndexPath(indexPath);
                store = fallbackStore;
        }

        // 注意: 自动加载需要在工作目录设置后进行
        // 将在 CodeToolProvider 或 IndexCommandHandler 中设置 workDir 后触发
        log.debug("VectorStore created, auto-load will be triggered after workDir is set");

        return store;
    }

    /**
     * 创建分块器
     * 根据 JimiConfig 中的 vector_index.enabled 配置条件性创建
     */
    @Bean
    @Autowired
    public Chunker chunker(JimiConfig jimiConfig) {
        VectorIndexConfig config = jimiConfig.getVectorIndex();

        if (!config.isEnabled()) {
            log.debug("Vector index is disabled, chunker will not be used");
        } else {
            log.info("Creating Chunker: SimpleChunker");
        }

        return new SimpleChunker();
    }


    // ==================== 配置加载内部工具类 ====================

    /**
     * 配置加载工具类
     * 负责从配置文件加载、保存和管理 Jimi 配置
     * 使用 YAML 格式
     */
    private static class ConfigLoaderHelper {

        private static final String RESOURCE_CONFIG_PATH = ".jimi/config.yml";

        /**
         * 获取配置文件路径
         */
        public static Path getConfigFilePath() {
            String userHome = System.getProperty("user.home");
            return Paths.get(userHome, RESOURCE_CONFIG_PATH);
        }

        /**
         * 加载配置
         * 配置优先级：自定义配置文件 > 默认配置文件 > 内置默认配置
         * 使用 YAML 格式
         */
        public static JimiConfig loadConfig(ObjectMapper objectMapper, Path customConfigFile) {
            Path configFile = customConfigFile != null ? customConfigFile : getConfigFilePath();

            JimiConfig config;
            if (Files.exists(configFile)) {
                log.debug("Loading config from file: {}", configFile);
                try {
                    // 使用 YAML ObjectMapper
                    ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                    yamlMapper.registerModule(new JavaTimeModule());
                    yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    config = yamlMapper.readValue(Files.newInputStream(configFile), JimiConfig.class);
                } catch (IOException e) {
                    throw new ConfigException("Failed to load config from file: " + configFile, e);
                }
            } else {
                log.debug("No config file found, creating default config");
                config = getDefaultConfig();
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
        public static void saveConfig(JimiConfig config, Path configFile) {
            try {
                // 确保目录存在
                Files.createDirectories(configFile.getParent());

                // 使用 YAML ObjectMapper
                ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                yamlMapper.registerModule(new JavaTimeModule());
                yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                yamlMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(Files.newOutputStream(configFile), config);

                log.info("Config saved to: {}", configFile);
            } catch (IOException e) {
                throw new ConfigException("Failed to save config to file: " + configFile, e);
            }
        }

        /**
         * 获取默认内置配置
         * 从 resources/.jimi/config.yml 加载
         */
        public static JimiConfig getDefaultConfig() {
            try {
                URL resource = ConfigLoaderHelper.class.getClassLoader().getResource(RESOURCE_CONFIG_PATH);
                if (resource != null) {
                    log.debug("Loading default config from classpath: {}", RESOURCE_CONFIG_PATH);
                    ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                    yamlMapper.registerModule(new JavaTimeModule());
                    yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    return yamlMapper.readValue(resource, JimiConfig.class);
                }
            } catch (IOException e) {
                log.warn("Failed to load default config from classpath: {}", e.getMessage());
            }

            throw new ConfigException("Failed to load default config from classpath: " + RESOURCE_CONFIG_PATH);
        }
    }

    /**
     * 公共方法：加载配置（用于测试）
     */
    public static JimiConfig loadConfig(ObjectMapper objectMapper, Path customConfigFile) {
        return ConfigLoaderHelper.loadConfig(objectMapper, customConfigFile);
    }
}
