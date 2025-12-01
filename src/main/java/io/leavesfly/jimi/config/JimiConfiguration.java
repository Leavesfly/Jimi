package io.leavesfly.jimi.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.jimi.graph.GraphManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jimi 应用配置类
 * 统一管理核心 Bean 的创建和配置
 */
@Configuration
public class JimiConfiguration {

    /**
     * ObjectMapper Bean - JSON 序列化/反序列化
     * 全局单例,用于所有 JSON 处理
     */
    @Bean
    @org.springframework.context.annotation.Primary
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
    @Autowired
    public JimiConfig jimiConfig(ConfigLoader configLoader) {
        return configLoader.loadConfig(null);
    }

    /**
     * GraphConfig Bean - 代码图配置
     * 从 application.yml 中加载 jimi.graph 配置
     */
    @Bean
    @ConfigurationProperties(prefix = "jimi.graph")
    public GraphConfig graphConfig() {
        return new GraphConfig();
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
}
