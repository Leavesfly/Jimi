package io.leavesfly.jimi.plugin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.leavesfly.jimi.plugin.spec.PluginScope;
import io.leavesfly.jimi.plugin.spec.PluginSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginLoader} 单元测试
 *
 * <p>手工构造 {@code yamlObjectMapper} 并注入到 {@link PluginLoader} 实例，
 * 避免启动完整 SpringBoot 上下文（会触发 {@code AgentSpecLoader} 找不到
 * Default-Agent 的副作用）。针对 {@code src/test/resources/plugins/hello-world/}
 * 夹具验证：
 * <ol>
 *   <li>目录扫描能正确发现插件</li>
 *   <li>{@code plugin.yaml} 能被完整反序列化</li>
 *   <li>作用域字段被正确填充</li>
 *   <li>兼容性校验逻辑正确</li>
 * </ol>
 */
class PluginLoaderTest {

    private PluginLoader pluginLoader;

    @BeforeEach
    void setUp() {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        pluginLoader = new PluginLoader();
        // 通过反射注入 @Autowired 字段，避免启动 Spring 容器
        ReflectionTestUtils.setField(pluginLoader, "yamlObjectMapper", yamlMapper);
    }

    /**
     * 定位 {@code src/test/resources/plugins/} 目录路径。
     *
     * <p>运行在 Maven 下时 classpath 会把该目录合并到 target/test-classes/plugins/，
     * 可以通过 ClassLoader 直接定位。
     */
    private Path resolveTestPluginsDir() throws Exception {
        URL url = Objects.requireNonNull(
                getClass().getClassLoader().getResource("plugins"),
                "test plugins/ directory missing on classpath");
        return Paths.get(url.toURI());
    }

    @Test
    @DisplayName("loadFromDirectory: 能加载 hello-world 测试插件")
    void loadHelloWorldPlugin() throws Exception {
        Path pluginsRoot = resolveTestPluginsDir();
        assertTrue(Files.isDirectory(pluginsRoot), "plugins root should exist");

        List<PluginSpec> plugins = pluginLoader.loadFromDirectory(pluginsRoot, PluginScope.USER);

        assertFalse(plugins.isEmpty(), "should find at least hello-world plugin");

        PluginSpec hello = plugins.stream()
                .filter(p -> "hello-world".equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("hello-world plugin not loaded"));

        assertEquals("1.0.0", hello.getVersion());
        assertEquals(PluginScope.USER, hello.getScope());
        assertNotNull(hello.getPluginDir());
        assertTrue(hello.getPluginDir().toString().endsWith("hello-world"));

        // provides 白名单被正确反序列化
        assertEquals(List.of("hello-skill"), hello.getProvides().getSkills());
        assertEquals(List.of("hello-hook"), hello.getProvides().getHooks());
        assertEquals(List.of("hello-cmd"), hello.getProvides().getCommands());
    }

    @Test
    @DisplayName("loadFromDirectory: 目录不存在时返回空列表")
    void nonExistentDirReturnsEmpty() {
        List<PluginSpec> plugins = pluginLoader.loadFromDirectory(
                Paths.get("/nonexistent/jimi/plugins"), PluginScope.USER);
        assertTrue(plugins.isEmpty());
    }

    @Test
    @DisplayName("loadFromDirectory: null 目录安全返回空列表")
    void nullDirReturnsEmpty() {
        List<PluginSpec> plugins = pluginLoader.loadFromDirectory(null, PluginScope.USER);
        assertTrue(plugins.isEmpty());
    }

    @Test
    @DisplayName("checkCompatibility: hello-world 插件能通过兼容性校验")
    void compatibilityPassesForHelloWorld() throws Exception {
        Path pluginsRoot = resolveTestPluginsDir();
        PluginSpec hello = pluginLoader.loadFromDirectory(pluginsRoot, PluginScope.USER).stream()
                .filter(p -> "hello-world".equals(p.getName()))
                .findFirst()
                .orElseThrow();

        PluginLoader.CheckResult result = pluginLoader.checkCompatibility(hello, "1.0.0");
        assertTrue(result.passed(), "expected compatibility pass, reason=" + result.reason());
    }

    @Test
    @DisplayName("checkCompatibility: 当 Jimi 版本过低时拒绝")
    void compatibilityFailsWhenJimiTooOld() throws Exception {
        Path pluginsRoot = resolveTestPluginsDir();
        PluginSpec hello = pluginLoader.loadFromDirectory(pluginsRoot, PluginScope.USER).stream()
                .filter(p -> "hello-world".equals(p.getName()))
                .findFirst()
                .orElseThrow();

        // hello-world 要求 jimi_version >= 0.0.1，0.0.0 不满足
        PluginLoader.CheckResult result = pluginLoader.checkCompatibility(hello, "0.0.0");
        assertFalse(result.passed());
        assertNotNull(result.reason());
    }

    @Test
    @DisplayName("parsePluginManifest: 解析后填充 pluginDir 与 scope")
    void parseFillsRuntimeFields() throws Exception {
        Path pluginsRoot = resolveTestPluginsDir();
        Path helloDir = pluginsRoot.resolve("hello-world");
        assertTrue(Files.isDirectory(helloDir));

        PluginSpec spec = pluginLoader.parsePluginManifest(helloDir, PluginScope.PROJECT);
        assertEquals("hello-world", spec.getName());
        assertEquals(PluginScope.PROJECT, spec.getScope());
        assertEquals(helloDir.toAbsolutePath(), spec.getPluginDir());
    }
}
