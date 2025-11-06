package io.leavesfly.jimi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.exception.ConfigException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigLoader 单元测试
 */
class ConfigLoaderTest {
    
    private ConfigLoader configLoader;
    private ObjectMapper objectMapper;
    
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        configLoader = new ConfigLoader(objectMapper);
    }
    
    @Test
    void testGetDefaultConfig() {
        JimiConfig config = configLoader.getDefaultConfig();
        
        assertNotNull(config);
        assertEquals("", config.getDefaultModel());
        assertNotNull(config.getModels());
        assertNotNull(config.getProviders());
        assertNotNull(config.getLoopControl());
        assertEquals(100, config.getLoopControl().getMaxStepsPerRun());
        assertEquals(3, config.getLoopControl().getMaxRetriesPerStep());
    }
    
    @Test
    void testSaveAndLoadConfig() {
        JimiConfig config = JimiConfig.builder()
                                     .defaultModel("test-model")
                                     .build();
        
        Path configFile = tempDir.resolve("test-config.json");
        configLoader.saveConfig(config, configFile);
        
        assertTrue(Files.exists(configFile));
        
        // 加载配置并验证
        JimiConfig loadedConfig = configLoader.loadConfig(configFile);
        assertNotNull(loadedConfig);
        assertEquals("test-model", loadedConfig.getDefaultModel());
        
        // 验证默认值是否被填充
        assertNotNull(loadedConfig.getLoopControl());
        assertEquals(100, loadedConfig.getLoopControl().getMaxStepsPerRun());
        assertEquals(3, loadedConfig.getLoopControl().getMaxRetriesPerStep());
    }
    
    @Test
    void testConfigValidation() {
        JimiConfig config = configLoader.getDefaultConfig();
        
        // 默认配置应该有效
        assertDoesNotThrow(config::validate);
    }
    
    @Test
    void testLoadNonExistentConfigCreatesDefault() {
        Path nonExistentFile = tempDir.resolve("non-existent-config.json");
        
        // 加载不存在的配置文件应该创建默认配置
        JimiConfig config = configLoader.loadConfig(nonExistentFile);
        
        assertNotNull(config);
        assertTrue(Files.exists(nonExistentFile));
        assertEquals("", config.getDefaultModel());  // 默认配置的默认模型
    }
    
    @Test
    void testLoadInvalidConfigThrowsException() {
        Path invalidConfigFile = tempDir.resolve("invalid-config.json");
        
        // 创建一个无效的JSON文件
        try {
            Files.writeString(invalidConfigFile, "{ invalid json }");
            
            // 应该抛出ConfigException
            assertThrows(ConfigException.class, () -> {
                configLoader.loadConfig(invalidConfigFile);
            });
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
    }
    
    @Test
    void testSaveConfigCreatesDirectory() {
        Path deepPath = tempDir.resolve("level1/level2/config.json");
        JimiConfig config = configLoader.getDefaultConfig();
        
        // 保存应该自动创建目录
        configLoader.saveConfig(config, deepPath);
        
        assertTrue(Files.exists(deepPath));
        assertTrue(Files.isDirectory(deepPath.getParent()));
    }
}
