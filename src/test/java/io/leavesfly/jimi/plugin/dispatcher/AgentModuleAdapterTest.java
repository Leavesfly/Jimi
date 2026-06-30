package io.leavesfly.jimi.plugin.dispatcher;

import io.leavesfly.jimi.core.agent.AgentRegistry;
import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.plugin.spec.PluginProvides;
import io.leavesfly.jimi.plugin.spec.PluginScope;
import io.leavesfly.jimi.plugin.spec.PluginSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentModuleAdapter} 单元测试
 */
class AgentModuleAdapterTest {

    private AgentModuleAdapter adapter;
    private AgentRegistry agentRegistry;
    private Path helloWorldDir;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new AgentModuleAdapter();
        agentRegistry = mock(AgentRegistry.class);
        ReflectionTestUtils.setField(adapter, "agentRegistry", agentRegistry);

        // mock registerAgentSpec 返回成功的 Mono
        when(agentRegistry.registerAgentSpec(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> Mono.just(mock(AgentSpec.class)));
        when(agentRegistry.unregisterAgentSpec(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

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
    @DisplayName("load: 白名单为空时放行所有发现项（含 .md 格式）")
    void loadWithoutWhitelistAllowsAll() {
        PluginSpec spec = buildSpec(PluginProvides.builder().build());

        ModuleLoadResult result = adapter.load(helloWorldDir, spec);

        assertTrue(result.isSuccess());
        List<String> items = result.getLoadedItems();
        assertTrue(items.contains("hello-agent"));
        assertTrue(items.contains("blocked-agent"));
        // Claude Code .md 格式的 agent 也应被发现（目录名为 md-agent）
        assertTrue(items.contains("md-agent"), "应发现 .md 格式的 md-agent");
    }

    @Test
    @DisplayName("unload: 与 load 对称，不抛异常")
    void unloadIsSymmetric() {
        PluginSpec spec = buildSpec(PluginProvides.builder().build());
        // 先 load 再 unload，验证对称性
        ModuleLoadResult result = adapter.load(helloWorldDir, spec);
        assertTrue(result.isSuccess());
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
