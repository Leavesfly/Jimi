package io.leavesfly.jimi.plugin.spec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginProvides} 白名单逻辑测试
 */
class PluginProvidesTest {

    @Test
    @DisplayName("空白名单视为全放行")
    void emptyWhitelistAllowsAll() {
        PluginProvides provides = PluginProvides.builder().build();

        assertTrue(provides.allowsSkill("any"));
        assertTrue(provides.allowsHook("any"));
        assertTrue(provides.allowsCommand("any"));
        assertTrue(provides.allowsMcpServer("any"));
        assertTrue(provides.allowsAgent("any"));
    }

    @Test
    @DisplayName("显式白名单仅允许列出的项")
    void explicitWhitelistFilters() {
        PluginProvides provides = PluginProvides.builder()
                .skills(List.of("skill-a", "skill-b"))
                .hooks(List.of("hook-x"))
                .commands(List.of("cmd-1"))
                .mcpServers(List.of("filesystem"))
                .agents(List.of("Security-Agent"))
                .build();

        assertTrue(provides.allowsSkill("skill-a"));
        assertTrue(provides.allowsSkill("skill-b"));
        assertFalse(provides.allowsSkill("skill-c"));

        assertTrue(provides.allowsHook("hook-x"));
        assertFalse(provides.allowsHook("hook-y"));

        assertTrue(provides.allowsCommand("cmd-1"));
        assertFalse(provides.allowsCommand("cmd-2"));

        assertTrue(provides.allowsMcpServer("filesystem"));
        assertFalse(provides.allowsMcpServer("other"));

        assertTrue(provides.allowsAgent("Security-Agent"));
        assertFalse(provides.allowsAgent("Other-Agent"));
    }
}
