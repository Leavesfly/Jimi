package io.leavesfly.jimi.plugin.spec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginModuleToggle} 测试
 */
class PluginModuleToggleTest {

    @Test
    @DisplayName("默认构造所有模块启用")
    void defaultAllEnabled() {
        PluginModuleToggle t = PluginModuleToggle.builder().build();
        assertTrue(t.isModuleEnabled("skills"));
        assertTrue(t.isModuleEnabled("hooks"));
        assertTrue(t.isModuleEnabled("commands"));
        assertTrue(t.isModuleEnabled("mcp"));
        assertTrue(t.isModuleEnabled("agents"));
    }

    @Test
    @DisplayName("模块名大小写不敏感")
    void caseInsensitive() {
        PluginModuleToggle t = PluginModuleToggle.builder().build();
        assertTrue(t.isModuleEnabled("SKILLS"));
        assertTrue(t.isModuleEnabled("Skills"));
    }

    @Test
    @DisplayName("未知模块视为未启用")
    void unknownModuleDisabled() {
        PluginModuleToggle t = PluginModuleToggle.builder().build();
        assertFalse(t.isModuleEnabled("unknown"));
        assertFalse(t.isModuleEnabled(null));
    }

    @Test
    @DisplayName("关闭单个模块不影响其他")
    void disableOneModule() {
        PluginModuleToggle t = PluginModuleToggle.builder()
                .hooks(false)
                .build();
        assertTrue(t.isModuleEnabled("skills"));
        assertFalse(t.isModuleEnabled("hooks"));
        assertTrue(t.isModuleEnabled("commands"));
    }
}
