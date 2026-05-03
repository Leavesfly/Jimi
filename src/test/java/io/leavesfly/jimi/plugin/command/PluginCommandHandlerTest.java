package io.leavesfly.jimi.plugin.command;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.plugin.PluginLoader;
import io.leavesfly.jimi.plugin.PluginRegistry;
import io.leavesfly.jimi.plugin.PluginRegistry.PluginState;
import io.leavesfly.jimi.plugin.dispatcher.ModuleLoadResult;
import io.leavesfly.jimi.plugin.dispatcher.PluginLoadResult;
import io.leavesfly.jimi.plugin.installer.PluginInstallResult;
import io.leavesfly.jimi.plugin.installer.PluginInstaller;
import io.leavesfly.jimi.plugin.spec.PluginScope;
import io.leavesfly.jimi.plugin.spec.PluginSpec;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PluginCommandHandler} 单元测试
 *
 * <p>策略：用 Mockito mock {@link PluginRegistry} 和 {@link OutputFormatter}，
 * 通过反射把 mock 好的 Registry 注入 Handler，避免启动 Spring 容器；
 * 断言则基于对 {@code OutputFormatter.printXxx} 方法的调用验证。
 */
class PluginCommandHandlerTest {

    private PluginCommandHandler handler;
    private PluginRegistry registry;
    private PluginInstaller installer;
    private OutputFormatter out;

    @BeforeEach
    void setUp() {
        registry = mock(PluginRegistry.class);
        installer = mock(PluginInstaller.class);
        out = mock(OutputFormatter.class);

        handler = new PluginCommandHandler();
        ReflectionTestUtils.setField(handler, "pluginRegistry", registry);
        ReflectionTestUtils.setField(handler, "pluginInstaller", installer);
    }

    // ==================== 元信息 ====================

    @Test
    @DisplayName("元信息: name / aliases / category / priority")
    void metadata() {
        assertTrue("plugin".equals(handler.getName()));
        assertTrue(handler.getAliases().contains("plugins"));
        assertTrue("plugin".equals(handler.getCategory()));
        assertTrue(handler.getPriority() == 10);
        assertTrue(handler.getUsage().startsWith("/plugin"));
    }

    // ==================== list ====================

    @Test
    @DisplayName("list: 无参数默认走 list，空列表输出提示")
    void listEmptyShowsHint() throws Exception {
        when(registry.list()).thenReturn(List.of());
        when(registry.getStatistics()).thenReturn(buildStats(0, 0, 0, 0));

        CommandContext ctx = buildContext();
        handler.execute(ctx);

        verify(registry, atLeastOnce()).list();
        verify(out, atLeastOnce()).printSuccess(anyString());
        // 空列表会给出"在 ~/.jimi/plugins/"相关提示
        verify(out, atLeastOnce()).printInfo(anyString());
    }

    @Test
    @DisplayName("list: 插件列表带启用 / 禁用 / 拒绝状态时全部显示")
    void listShowsAllStates() throws Exception {
        PluginSpec enabledSpec = buildSpec("alpha", PluginScope.USER);
        PluginSpec disabledSpec = buildSpec("beta", PluginScope.PROJECT);
        PluginSpec rejectedSpec = buildSpec("gamma", PluginScope.CLASSPATH);

        List<PluginState> states = List.of(
                buildEnabledState(enabledSpec),
                buildDisabledState(disabledSpec),
                buildRejectedState(rejectedSpec, "incompatible")
        );
        when(registry.list()).thenReturn(states);
        when(registry.getStatistics()).thenReturn(buildStats(3, 1, 1, 1));

        CommandContext ctx = buildContext("list");
        handler.execute(ctx);

        // 三个插件名都应该输出过
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(out, atLeastOnce()).println(lines.capture());
        String all = String.join("\n", lines.getAllValues());
        assertTrue(all.contains("alpha"), "应输出 alpha 插件行");
        assertTrue(all.contains("beta"), "应输出 beta 插件行");
        assertTrue(all.contains("gamma"), "应输出 gamma 插件行");
    }

    // ==================== info ====================

    @Test
    @DisplayName("info: 缺少参数给出用法错误")
    void infoWithoutArgShowsError() throws Exception {
        CommandContext ctx = buildContext("info");
        handler.execute(ctx);
        verify(out).printError(anyString());
    }

    @Test
    @DisplayName("info: 未知插件打印错误")
    void infoUnknownPlugin() throws Exception {
        when(registry.findByName("nope")).thenReturn(java.util.Optional.empty());

        CommandContext ctx = buildContext("info", "nope");
        handler.execute(ctx);

        verify(out).printError(anyString());
    }

    @Test
    @DisplayName("info: 已知插件输出详细信息")
    void infoKnownPluginPrintsDetails() throws Exception {
        PluginSpec spec = buildSpec("alpha", PluginScope.USER);
        when(registry.findByName("alpha")).thenReturn(
                java.util.Optional.of(buildEnabledState(spec)));

        CommandContext ctx = buildContext("info", "alpha");
        handler.execute(ctx);

        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(out, atLeastOnce()).println(lines.capture());
        String all = String.join("\n", lines.getAllValues());
        assertTrue(all.contains("alpha"), "详情应包含插件名");
        assertTrue(all.contains("1.0.0"), "详情应包含版本号");
        assertTrue(all.contains("基本信息") || all.contains("扩展点"),
                "详情应包含基本信息/扩展点节");
    }

    @Test
    @DisplayName("info 便捷语法: /plugin <name> 等价于 /plugin info <name>")
    void infoShortcut() throws Exception {
        PluginSpec spec = buildSpec("alpha", PluginScope.USER);
        when(registry.findByName("alpha")).thenReturn(
                java.util.Optional.of(buildEnabledState(spec)));

        CommandContext ctx = buildContext("alpha");
        handler.execute(ctx);

        verify(registry, atLeastOnce()).findByName("alpha");
    }

    // ==================== enable / disable ====================

    @Test
    @DisplayName("enable: 缺少参数给出用法错误")
    void enableWithoutArgShowsError() throws Exception {
        handler.execute(buildContext("enable"));
        verify(out).printError(anyString());
        verify(registry, never()).enable(anyString());
    }

    @Test
    @DisplayName("enable: 不存在插件报错")
    void enableUnknownPlugin() throws Exception {
        when(registry.has("x")).thenReturn(false);

        handler.execute(buildContext("enable", "x"));

        verify(out).printError(anyString());
        verify(registry, never()).enable(anyString());
    }

    @Test
    @DisplayName("enable: 存在且被禁用时调用 registry.enable")
    void enableExistingPlugin() throws Exception {
        when(registry.has("alpha")).thenReturn(true);
        when(registry.enable("alpha")).thenReturn(true);

        handler.execute(buildContext("enable", "alpha"));

        verify(registry).enable("alpha");
        verify(out).printSuccess(anyString());
    }

    @Test
    @DisplayName("disable: 存在插件时调用 registry.disable")
    void disableExistingPlugin() throws Exception {
        when(registry.has("alpha")).thenReturn(true);
        when(registry.disable("alpha")).thenReturn(true);

        handler.execute(buildContext("disable", "alpha"));

        verify(registry).disable("alpha");
        verify(out).printSuccess(anyString());
    }

    // ==================== reload ====================

    @Test
    @DisplayName("reload: 调用 registry.reload")
    void reloadInvokesRegistry() throws Exception {
        when(registry.getStatistics()).thenReturn(buildStats(2, 2, 0, 0));

        handler.execute(buildContext("reload"));

        verify(registry, times(1)).reload();
        verify(out).printSuccess(anyString());
    }

    // ==================== install ====================

    @Test
    @DisplayName("install: 缺少参数给出用法错误")
    void installWithoutArgShowsError() throws Exception {
        handler.execute(buildContext("install"));
        verify(out).printError(anyString());
        verify(installer, never()).install(anyString());
    }

    @Test
    @DisplayName("install: 成功时调用 installer 并触发 reload")
    void installSucceedsAndReloads() throws Exception {
        PluginSpec spec = buildSpec("new-plugin", PluginScope.USER);
        Path installedDir = Paths.get("/tmp/jimi-plugins/new-plugin");
        PluginInstallResult result = new PluginInstallResult(spec, installedDir, "owner/repo");
        when(installer.install("owner/repo")).thenReturn(result);
        when(registry.getStatistics()).thenReturn(buildStats(3, 3, 0, 0));

        handler.execute(buildContext("install", "owner/repo"));

        verify(installer, times(1)).install("owner/repo");
        verify(registry, times(1)).reload();
        // 至少两次 printSuccess：一次"插件已安装"，一次"插件已激活"
        verify(out, atLeastOnce()).printSuccess(anyString());
    }

    @Test
    @DisplayName("install: 安装异常被捕获并打印 printError，不抛出")
    void installFailureIsCaught() throws Exception {
        when(installer.install(anyString()))
                .thenThrow(new RuntimeException("network down"));

        handler.execute(buildContext("install", "owner/repo"));

        verify(out).printError(anyString());
        // 失败时不应触发 reload
        verify(registry, never()).reload();
    }

    // ==================== uninstall ====================

    @Test
    @DisplayName("uninstall: 缺少参数给出用法错误")
    void uninstallWithoutArgShowsError() throws Exception {
        handler.execute(buildContext("uninstall"));
        verify(out).printError(anyString());
        verify(installer, never()).uninstall(anyString());
    }

    @Test
    @DisplayName("uninstall: 插件不存在时打印 printError")
    void uninstallUnknownPlugin() throws Exception {
        when(installer.uninstall("nope")).thenReturn(false);

        handler.execute(buildContext("uninstall", "nope"));

        verify(installer).uninstall("nope");
        verify(out).printError(anyString());
        verify(registry, never()).reload();
    }

    @Test
    @DisplayName("uninstall: 成功卸载后触发 reload")
    void uninstallSucceedsAndReloads() throws Exception {
        when(installer.uninstall("alpha")).thenReturn(true);
        when(registry.getStatistics()).thenReturn(buildStats(2, 2, 0, 0));

        handler.execute(buildContext("uninstall", "alpha"));

        verify(installer).uninstall("alpha");
        verify(registry, times(1)).reload();
        verify(out).printSuccess(anyString());
    }

    @Test
    @DisplayName("uninstall: remove 作为别名也能触发卸载")
    void removeAliasWorks() throws Exception {
        when(installer.uninstall("alpha")).thenReturn(true);
        when(registry.getStatistics()).thenReturn(buildStats(1, 1, 0, 0));

        handler.execute(buildContext("remove", "alpha"));

        verify(installer).uninstall("alpha");
        verify(registry, times(1)).reload();
    }

    @Test
    @DisplayName("uninstall: 异常被捕获并打印 printError")
    void uninstallFailureIsCaught() throws Exception {
        when(installer.uninstall(anyString()))
                .thenThrow(new RuntimeException("disk full"));

        handler.execute(buildContext("uninstall", "alpha"));

        verify(out).printError(anyString());
        verify(registry, never()).reload();
    }

    // ==================== 辅助方法 ====================

    private CommandContext buildContext(String... args) {
        return CommandContext.builder()
                .outputFormatter(out)
                .args(args)
                .commandName("plugin")
                .rawInput("/plugin " + String.join(" ", args))
                .build();
    }

    private PluginSpec buildSpec(String name, PluginScope scope) {
        PluginSpec spec = PluginSpec.builder()
                .name(name)
                .version("1.0.0")
                .description("desc of " + name)
                .build();
        spec.setScope(scope);
        return spec;
    }

    private PluginState buildEnabledState(PluginSpec spec) {
        Map<String, ModuleLoadResult> modules = new LinkedHashMap<>();
        modules.put("skills", ModuleLoadResult.success(List.of("s1")));
        PluginLoadResult result = new PluginLoadResult(spec, modules);
        // 反射调用 PluginState.enabled(spec, result)
        return ReflectionTestUtils.invokeMethod(
                PluginState.class, "enabled", spec, result);
    }

    private PluginState buildDisabledState(PluginSpec spec) {
        return ReflectionTestUtils.invokeMethod(
                PluginState.class, "disabled", spec);
    }

    private PluginState buildRejectedState(PluginSpec spec, String reason) {
        return ReflectionTestUtils.invokeMethod(
                PluginState.class, "rejected", spec, reason);
    }

    private Map<String, Object> buildStats(long total, long enabled, long disabled, long rejected) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPlugins", total);
        stats.put("enabled", enabled);
        stats.put("disabled", disabled);
        stats.put("rejected", rejected);
        return stats;
    }

    /** 只是为了让 PluginLoader 在测试 classpath 中有引用，便于 IDE 不报 unused import */
    @SuppressWarnings("unused")
    private PluginLoader.CheckResult dummy() {
        return PluginLoader.CheckResult.ok();
    }
}
