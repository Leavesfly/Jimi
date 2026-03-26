package io.leavesfly.jimi.command.custom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.leavesfly.jimi.common.YamlConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CustomCommandLoader 测试
 */
class CustomCommandLoaderTest {
    
    private CustomCommandLoader loader;
    
    @BeforeEach
    void setUp() {
        ObjectMapper yamlObjectMapper = new ObjectMapper(new YAMLFactory());
        
        // 创建 YamlConfigLoader 并注入 yamlObjectMapper
        YamlConfigLoader yamlConfigLoader = new YamlConfigLoader();
        try {
            var yamlMapperField = YamlConfigLoader.class.getDeclaredField("yamlObjectMapper");
            yamlMapperField.setAccessible(true);
            yamlMapperField.set(yamlConfigLoader, yamlObjectMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        
        // 创建 CustomCommandLoader 并注入 yamlConfigLoader
        loader = new CustomCommandLoader();
        try {
            var loaderField = CustomCommandLoader.class.getDeclaredField("yamlConfigLoader");
            loaderField.setAccessible(true);
            loaderField.set(loader, yamlConfigLoader);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    @Test
    void testParseScriptCommand(@TempDir Path tempDir) throws Exception {
        // 创建测试配置文件
        Path configFile = tempDir.resolve("test.yaml");
        String yaml = """
                name: "test-cmd"
                description: "测试命令"
                category: "test"
                execution:
                  type: "script"
                  script: "echo hello"
                """;
        Files.writeString(configFile, yaml);
        
        // 解析
        CustomCommandSpec spec = loader.parseCommandFile(configFile);
        
        // 验证
        assertNotNull(spec);
        assertEquals("test-cmd", spec.getName());
        assertEquals("测试命令", spec.getDescription());
        assertEquals("test", spec.getCategory());
        assertEquals("script", spec.getExecution().getType());
        assertEquals("echo hello", spec.getExecution().getScript());
    }
    
    @Test
    void testParseCommandWithParameters(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("test.yaml");
        String yaml = """
                name: "test-cmd"
                description: "测试命令"
                parameters:
                  - name: "param1"
                    type: "string"
                    defaultValue: "default"
                  - name: "param2"
                    type: "boolean"
                    required: true
                execution:
                  type: "script"
                  script: "echo test"
                """;
        Files.writeString(configFile, yaml);
        
        CustomCommandSpec spec = loader.parseCommandFile(configFile);
        
        assertNotNull(spec);
        assertEquals(2, spec.getParameters().size());
        
        ParameterSpec param1 = spec.getParameters().get(0);
        assertEquals("param1", param1.getName());
        assertEquals("string", param1.getType());
        assertEquals("default", param1.getDefaultValue());
        assertFalse(param1.isRequired());
        
        ParameterSpec param2 = spec.getParameters().get(1);
        assertEquals("param2", param2.getName());
        assertEquals("boolean", param2.getType());
        assertTrue(param2.isRequired());
    }
    
    @Test
    void testParseCompositeCommand(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("test.yaml");
        String yaml = """
                name: "test-cmd"
                description: "组合命令"
                execution:
                  type: "composite"
                  steps:
                    - type: "command"
                      command: "/reset"
                    - type: "script"
                      script: "echo test"
                      continueOnFailure: true
                """;
        Files.writeString(configFile, yaml);
        
        CustomCommandSpec spec = loader.parseCommandFile(configFile);
        
        assertNotNull(spec);
        assertEquals("composite", spec.getExecution().getType());
        assertEquals(2, spec.getExecution().getSteps().size());
        
        CompositeStepSpec step1 = spec.getExecution().getSteps().get(0);
        assertEquals("command", step1.getType());
        assertEquals("/reset", step1.getCommand());
        
        CompositeStepSpec step2 = spec.getExecution().getSteps().get(1);
        assertEquals("script", step2.getType());
        assertEquals("echo test", step2.getScript());
        assertTrue(step2.isContinueOnFailure());
    }
    
    @Test
    void testParsePreconditions(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("test.yaml");
        String yaml = """
                name: "test-cmd"
                description: "测试命令"
                execution:
                  type: "script"
                  script: "echo test"
                preconditions:
                  - type: "file_exists"
                    path: "pom.xml"
                    errorMessage: "pom.xml not found"
                  - type: "command_exists"
                    command: "mvn"
                """;
        Files.writeString(configFile, yaml);
        
        CustomCommandSpec spec = loader.parseCommandFile(configFile);
        
        assertNotNull(spec);
        assertEquals(2, spec.getPreconditions().size());
        
        PreconditionSpec pre1 = spec.getPreconditions().get(0);
        assertEquals("file_exists", pre1.getType());
        assertEquals("pom.xml", pre1.getPath());
        assertEquals("pom.xml not found", pre1.getErrorMessage());
        
        PreconditionSpec pre2 = spec.getPreconditions().get(1);
        assertEquals("command_exists", pre2.getType());
        assertEquals("mvn", pre2.getCommand());
    }
    
    @Test
    void testValidation_MissingName() {
        CustomCommandSpec spec = CustomCommandSpec.builder()
                .description("test")
                .execution(ExecutionSpec.builder()
                        .type("script")
                        .script("echo test")
                        .build())
                .build();
        
        assertThrows(IllegalArgumentException.class, spec::validate);
    }
    
    @Test
    void testValidation_InvalidExecutionType() {
        CustomCommandSpec spec = CustomCommandSpec.builder()
                .name("test")
                .description("test")
                .execution(ExecutionSpec.builder()
                        .type("invalid")
                        .build())
                .build();
        
        assertThrows(IllegalArgumentException.class, spec::validate);
    }
}
