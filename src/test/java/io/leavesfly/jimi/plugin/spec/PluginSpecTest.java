package io.leavesfly.jimi.plugin.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginSpec} 及其 YAML 反序列化测试
 */
class PluginSpecTest {

    private static ObjectMapper yamlMapper;

    @BeforeAll
    static void setUp() {
        yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    @Test
    @DisplayName("validate: 缺少 name 抛异常")
    void validateRequiresName() {
        PluginSpec spec = PluginSpec.builder().version("1.0.0").description("d").build();
        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    @DisplayName("validate: 缺少 version 抛异常")
    void validateRequiresVersion() {
        PluginSpec spec = PluginSpec.builder().name("x").description("d").build();
        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    @DisplayName("validate: 缺少 description 抛异常")
    void validateRequiresDescription() {
        PluginSpec spec = PluginSpec.builder().name("x").version("1.0.0").build();
        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    @DisplayName("validate: 三个必需字段齐全时通过")
    void validatePassesWithAllRequired() {
        PluginSpec spec = PluginSpec.builder()
                .name("x").version("1.0.0").description("d").build();
        spec.validate();
    }

    @Test
    @DisplayName("isEnabled: 默认为 true")
    void enabledByDefault() {
        PluginSpec spec = PluginSpec.builder()
                .name("x").version("1.0.0").description("d").build();
        assertTrue(spec.isEnabled());
    }

    @Test
    @DisplayName("isModuleEnabled: 插件禁用时所有模块都禁用")
    void disabledPluginDisablesAllModules() {
        PluginSpec spec = PluginSpec.builder()
                .name("x").version("1.0.0").description("d")
                .defaults(PluginDefaults.builder().enabled(false).build())
                .build();
        assertFalse(spec.isModuleEnabled("skills"));
        assertFalse(spec.isModuleEnabled("hooks"));
    }

    @Test
    @DisplayName("YAML 反序列化：最小清单")
    void deserializeMinimal() throws Exception {
        String yaml = """
                name: hello
                version: 1.0.0
                description: minimal plugin
                """;
        PluginSpec spec = yamlMapper.readValue(yaml, PluginSpec.class);
        assertEquals("hello", spec.getName());
        assertEquals("1.0.0", spec.getVersion());
        assertEquals("minimal plugin", spec.getDescription());
        assertNotNull(spec.getProvides());
        assertNotNull(spec.getDefaults());
    }

    @Test
    @DisplayName("YAML 反序列化：完整清单含 provides / compatibility / defaults")
    void deserializeFull() throws Exception {
        String yaml = """
                name: full-plugin
                version: 2.1.0
                description: full feature plugin
                author: tester
                license: Apache-2.0
                compatibility:
                  jimi_version: ">=0.1.0"
                  java_version: ">=17"
                  os: [linux, mac]
                provides:
                  skills: [s1, s2]
                  hooks: [h1]
                  commands: [c1]
                  mcp_servers: [m1]
                  agents: [A1]
                defaults:
                  enabled: true
                  modules:
                    skills: true
                    hooks: false
                    commands: true
                    mcp: true
                    agents: true
                dependencies:
                  - name: git-toolkit
                    version: ">=0.3.0"
                """;
        PluginSpec spec = yamlMapper.readValue(yaml, PluginSpec.class);

        assertEquals("full-plugin", spec.getName());
        assertEquals("2.1.0", spec.getVersion());
        assertEquals("tester", spec.getAuthor());
        assertEquals("Apache-2.0", spec.getLicense());

        assertNotNull(spec.getCompatibility());
        assertEquals(">=0.1.0", spec.getCompatibility().getJimiVersion());
        assertEquals(List.of("linux", "mac"), spec.getCompatibility().getOs());

        assertEquals(List.of("s1", "s2"), spec.getProvides().getSkills());
        assertEquals(List.of("m1"), spec.getProvides().getMcpServers());

        assertTrue(spec.isEnabled());
        assertTrue(spec.isModuleEnabled("skills"));
        assertFalse(spec.isModuleEnabled("hooks"));
        assertTrue(spec.isModuleEnabled("commands"));

        assertEquals(1, spec.getDependencies().size());
        assertEquals("git-toolkit", spec.getDependencies().get(0).getName());
    }
}
