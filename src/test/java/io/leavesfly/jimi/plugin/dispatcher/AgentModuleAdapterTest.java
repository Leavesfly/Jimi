package io.leavesfly.jimi.plugin.dispatcher;

import io.leavesfly.jimi.plugin.spec.PluginProvides;
import io.leavesfly.jimi.plugin.spec.PluginScope;
import io.leavesfly.jimi.plugin.spec.PluginSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentModuleAdapter} 单元测试
 */
class AgentModuleAdapterTest {

    private AgentModuleAdapter adapter;
    private Path helloWorldDir;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new AgentModuleAdapter();
        URL url = Objects.requireNonNull(
                getClass().getClassLoader().getResource("plugins/hello-world"),
                "hello-world fixture missing on classpath");
        helloWorldDir = Paths.get(url.toURI());
        assertTrue(Files.isDirectory(helloWorldDir));
    }

    @Test
    @DisplayName("getModuleName 返回 'agents'")
    void moduleName() {
        assertEquals("agents", adapter.getModuleName());
    }

    @Test
    @DisplayName("supports: 含 agents/ 目录时返回 true")
    void supportsWhenDirExists() {
        assertTrue(adapter.supports(helloWorldDir));
    }

    @Test
    @DisplayName("supports: 目录不存在时返回 false")
    void supportsWhenDirMissing() {
        assertFalse(adapter.supports(Paths.get("/nonexistent/plugin")));
        assertFalse(adapter.supports(null));
    }

    @Test
    @DisplayName("load: 按 provides 白名单过滤，仅发现 hello-agent")
    void loadFiltersByWhitelist() {
        PluginSpec spec = buildSpec(
                PluginProvides.builder().agents(List.of("hello-agent")).build());

        ModuleLoadResult result = adapter.load(helloWorldDir, spec);

        assertTrue(result.isSuccess());
        List<String> items = result.getLoadedItems();
        assertTrue(items.contains("hello-agent"), "应发现 hello-agent");
        assertFalse(items.contains("blocked-agent"), "blocked-agent 不在白名单应被过滤");
        assertEquals(1, items.size());
    }

    @Test
    @DisplayName("load: 白名单为空时放行所有发现项")
    void loadWithoutWhitelistAllowsAll() {
        PluginSpec spec = buildSpec(PluginProvides.builder().build());

        ModuleLoadResult result = adapter.load(helloWorldDir, spec);

        assertTrue(result.isSuccess());
        List<String> items = result.getLoadedItems();
        assertTrue(items.contains("hello-agent"));
        assertTrue(items.contains("blocked-agent"));
    }

    @Test
    @DisplayName("unload: MVP 阶段为 no-op，不抛异常")
    void unloadIsNoop() {
        PluginSpec spec = buildSpec(PluginProvides.builder().build());
        ModuleLoadResult result = ModuleLoadResult.success(List.of("hello-agent"));
        // 不应抛异常
        adapter.unload(spec, result);
        adapter.unload(spec, null);
    }

    private PluginSpec buildSpec(PluginProvides provides) {
        PluginSpec spec = PluginSpec.builder()
                .name("hello-world")
                .version("1.0.0")
                .description("fixture")
                .provides(provides)
                .build();
        spec.setScope(PluginScope.USER);
        return spec;
    }
}
