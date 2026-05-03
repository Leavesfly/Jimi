package io.leavesfly.jimi.plugin;

import io.leavesfly.jimi.plugin.PluginLoader.LoadedPlugin;
import io.leavesfly.jimi.plugin.dispatcher.McpModuleAdapter;
import io.leavesfly.jimi.plugin.dispatcher.PluginDispatcher;
import io.leavesfly.jimi.plugin.dispatcher.PluginLoadResult;
import io.leavesfly.jimi.plugin.spec.PluginScope;
import io.leavesfly.jimi.plugin.spec.PluginSpec;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 插件注册中心
 *
 * <p>对外的核心门面类，聚合 {@link PluginLoader} 与 {@link PluginDispatcher}：
 * <ul>
 *   <li>启动时 {@code @PostConstruct} 自动加载 CLASSPATH + USER 两层插件</li>
 *   <li>运行时提供 {@link #loadProjectPlugins(Path)} 接入项目级插件</li>
 *   <li>提供 {@code list} / {@code findByName} / {@code enable} / {@code disable} / {@code reload} 等管理 API</li>
 * </ul>
 *
 * <p>通过 {@link DependsOn} 显式声明依赖：必须在 Jimi 原生的 Skills/Hooks/Commands
 * 三个 Registry 之后初始化，确保其自身加载完成后 Plugin 才往里追加内容。
 *
 * <p>线程安全：{@link #loadedPlugins} 使用 {@link ConcurrentHashMap}。
 *
 * @see PluginLoader 插件清单加载器
 * @see PluginDispatcher 扩展点分发器
 */
@Slf4j
@Service
@DependsOn({"skillRegistry", "hookRegistry", "customCommandRegistry"})
public class PluginRegistry {

    /** 当前 Jimi 版本（从配置注入，用于插件兼容性校验） */
    @Value("${jimi.version:0.1.0}")
    private String jimiVersion;

    @Autowired
    private PluginLoader pluginLoader;

    @Autowired
    private PluginDispatcher pluginDispatcher;

    /**
     * MCP 模块 Adapter，用于暴露"发现但未注入"的 MCP 配置文件查询接口。
     *
     * <p>单独注入而不是经由 {@link PluginDispatcher}，是因为 MCP 的数据流特殊：
     * <ul>
     *   <li>其他模块（Skills/Hooks/Commands）的产物直接落到对应 Registry，随插件生效</li>
     *   <li>MCP 的 {@code mcpConfigFiles} 由 {@code JimiFactory} 在 Engine 创建时一次性传入，
     *       运行时不支持增删——因此 Adapter 必须把发现结果暴露出来让 Factory 自行消费</li>
     * </ul>
     */
    @Autowired
    private McpModuleAdapter mcpModuleAdapter;

    /**
     * 所有已加载插件的状态缓存。
     *
     * <p>Key：插件名；Value：完整状态对象（含规范 + 加载结果 + 启用标志）。
     * 使用 {@link ConcurrentHashMap} 保证并发访问安全。
     */
    private final Map<String, PluginState> loadedPlugins = new ConcurrentHashMap<>();

    /**
     * 最近一次 {@link #loadProjectPlugins(Path)} 传入的项目根目录，
     * 用于 {@link #reload()} 重新扫描项目级插件。
     *
     * <p>使用 {@link AtomicReference} 保证可见性与无锁更新。
     */
    private final AtomicReference<Path> currentProjectDir = new AtomicReference<>();

    /**
     * 启动时加载 CLASSPATH + USER 两层插件。
     *
     * <p>PROJECT 层插件不在此处加载——因为项目根目录需等 {@code JimiFactory}
     * 在会话启动时显式触发 {@link #loadProjectPlugins(Path)}。
     */
    @PostConstruct
    public void initialize() {
        log.info("Initializing PluginRegistry (jimiVersion={})...", jimiVersion);

        List<LoadedPlugin> classpathPlugins = pluginLoader.loadAllFromClasspath();
        List<LoadedPlugin> userPlugins = pluginLoader.loadAllFromUserHome();

        for (LoadedPlugin loaded : classpathPlugins) {
            dispatchAndRemember(loaded);
        }
        for (LoadedPlugin loaded : userPlugins) {
            dispatchAndRemember(loaded);
        }

        log.info("PluginRegistry initialized with {} plugin(s) tracked", loadedPlugins.size());
    }

    /**
     * 加载项目级插件（{@code &lt;project&gt;/.jimi/plugins/}）。
     *
     * <p>通常由 {@code JimiFactory} 在会话创建时调用。项目级插件优先级最高，
     * 同名扩展项会覆盖 CLASSPATH / USER 级的实现。
     *
     * <p>该方法会<b>缓存</b>传入的 {@code projectDir}，后续 {@link #reload()}
     * 会基于此路径重新扫描 PROJECT 层，避免 reload 丢失项目级插件。
     *
     * @param projectDir 项目根目录
     */
    public void loadProjectPlugins(Path projectDir) {
        if (projectDir == null) {
            log.debug("Project dir is null, skip loading project plugins");
            return;
        }
        log.info("Loading project plugins from: {}", projectDir);
        currentProjectDir.set(projectDir);

        List<LoadedPlugin> projectPlugins = pluginLoader.loadAllFromProject(projectDir);
        for (LoadedPlugin loaded : projectPlugins) {
            dispatchAndRemember(loaded);
        }
        log.info("Loaded {} project plugin(s) (incl. rejected)", projectPlugins.size());
    }

    /**
     * 统一的"校验结果分派 + 记录"流程。
     *
     * <p>处理四种状态：
     * <ol>
     *   <li>{@link LoadedPlugin#isRejected()} == true → 以 {@code rejected} 状态记录</li>
     *   <li>{@link PluginSpec#isEnabled()} == false → 以 {@code disabled} 状态记录</li>
     *   <li>分发器抛异常 → 以 {@code rejected} 状态记录，原因为异常信息</li>
     *   <li>以上都不是 → 以 {@code enabled} 状态记录，保留 {@link PluginLoadResult}</li>
     * </ol>
     *
     * <p>任何情况都不抛异常——保证任何一个插件加载失败都不会阻塞其他插件。
     *
     * @param loaded 插件加载结果（来自 {@link PluginLoader#loadAllFromDirectory}）
     */
    private void dispatchAndRemember(LoadedPlugin loaded) {
        PluginSpec spec = loaded.getSpec();

        // 1. 兼容性 / 环境依赖已被 PluginLoader 校验过——直接消费结果
        if (loaded.isRejected()) {
            log.warn("Plugin '{}' rejected: {}", spec.getName(), loaded.getRejectReason());
            loadedPlugins.put(spec.getName(), PluginState.rejected(spec, loaded.getRejectReason()));
            return;
        }

        // 2. 全局启用开关
        if (!spec.isEnabled()) {
            log.info("Plugin '{}' is disabled by manifest, skipping dispatch", spec.getName());
            loadedPlugins.put(spec.getName(), PluginState.disabled(spec));
            return;
        }

        // 3. 分发加载（异常也记录为 rejected，不污染状态表）
        try {
            PluginLoadResult result = pluginDispatcher.dispatch(spec);
            loadedPlugins.put(spec.getName(), PluginState.enabled(spec, result));
            log.info("Plugin '{}' loaded (scope={}, version={}, modules={})",
                    spec.getName(), spec.getScope(), spec.getVersion(),
                    result.getModuleResults().keySet());
        } catch (Exception e) {
            String reason = "dispatch failed: " + e.getMessage();
            log.error("Plugin '{}' dispatch threw exception", spec.getName(), e);
            loadedPlugins.put(spec.getName(), PluginState.rejected(spec, reason));
        }
    }

    /**
     * 禁用一个已加载插件。
     *
     * <p>会触发 {@link PluginDispatcher#unload} 反向卸载各扩展点。
     * 各模块的 {@link io.leavesfly.jimi.plugin.dispatcher.PluginModuleAdapter#unload}
     * 负责从对应 Registry 反注册已加载的扩展项。
     *
     * @param pluginName 插件名
     * @return 是否执行了禁用动作（插件不存在或已禁用时返回 {@code false}）
     */
    public boolean disable(String pluginName) {
        PluginState state = loadedPlugins.get(pluginName);
        if (state == null) {
            log.warn("disable: plugin '{}' not found", pluginName);
            return false;
        }
        if (!state.enabled) {
            log.debug("disable: plugin '{}' already disabled", pluginName);
            return false;
        }
        pluginDispatcher.unload(state.loadResult);
        loadedPlugins.put(pluginName, PluginState.disabled(state.spec));
        log.info("Plugin '{}' disabled", pluginName);
        return true;
    }

    /**
     * 重新启用一个已被禁用的插件。
     *
     * @param pluginName 插件名
     * @return 是否执行了启用动作（插件不存在或已启用时返回 {@code false}）
     */
    public boolean enable(String pluginName) {
        PluginState state = loadedPlugins.get(pluginName);
        if (state == null) {
            log.warn("enable: plugin '{}' not found", pluginName);
            return false;
        }
        if (state.enabled) {
            log.debug("enable: plugin '{}' already enabled", pluginName);
            return false;
        }
        PluginLoadResult result = pluginDispatcher.dispatch(state.spec);
        loadedPlugins.put(pluginName, PluginState.enabled(state.spec, result));
        log.info("Plugin '{}' enabled", pluginName);
        return true;
    }

    /**
     * 重新加载所有插件。
     *
     * <p><b>关键语义</b>：不再复用旧的 {@link PluginSpec}，而是<b>重新从磁盘扫描</b>
     * 全部三层（CLASSPATH + USER + PROJECT）。这样：
     * <ul>
     *   <li>{@code /plugin install} 后 reload 能加载新装插件</li>
     *   <li>{@code /plugin uninstall} 后 reload 能移除已删除插件（不再有"幽灵插件"）</li>
     *   <li>用户在磁盘上手动编辑 {@code plugin.yaml} 后 reload 能立即生效</li>
     * </ul>
     *
     * <p>PROJECT 层依赖 {@link #currentProjectDir} 缓存——如果先前从未调用
     * {@link #loadProjectPlugins}，reload 时 PROJECT 层会被跳过。
     *
     * <p>加载顺序仍为 CLASSPATH → USER → PROJECT，保持原有的三层优先级。
     */
    public void reload() {
        int previousCount = loadedPlugins.size();
        Path projectDir = currentProjectDir.get();
        log.info("Reloading all plugins from disk (previous={}, project_dir={})...",
                previousCount, projectDir);

        // 1. 反向卸载所有已启用的扩展点
        for (PluginState state : new ArrayList<>(loadedPlugins.values())) {
            if (state.enabled && state.loadResult != null) {
                pluginDispatcher.unload(state.loadResult);
            }
        }

        // 2. 清空状态表，dispatchAndRemember 会按照磁盘扫描结果重新填充
        loadedPlugins.clear();

        // 3. 重新从磁盘扫描三层插件目录
        List<LoadedPlugin> classpathPlugins = pluginLoader.loadAllFromClasspath();
        List<LoadedPlugin> userPlugins = pluginLoader.loadAllFromUserHome();
        List<LoadedPlugin> projectPlugins = projectDir != null
                ? pluginLoader.loadAllFromProject(projectDir)
                : Collections.emptyList();

        // 4. 按 CLASSPATH → USER → PROJECT 顺序重新分发
        for (LoadedPlugin loaded : classpathPlugins) {
            dispatchAndRemember(loaded);
        }
        for (LoadedPlugin loaded : userPlugins) {
            dispatchAndRemember(loaded);
        }
        for (LoadedPlugin loaded : projectPlugins) {
            dispatchAndRemember(loaded);
        }

        log.info("Reload complete: {} plugin(s) tracked (was {})",
                loadedPlugins.size(), previousCount);
    }

    /**
     * 按名称查找插件状态。
     *
     * @param pluginName 插件名
     * @return 插件状态
     */
    public Optional<PluginState> findByName(String pluginName) {
        return Optional.ofNullable(loadedPlugins.get(pluginName));
    }

    /**
     * 获取所有已加载插件的状态（只读快照）。
     *
     * @return 插件状态列表（按名称排序）
     */
    public List<PluginState> list() {
        List<PluginState> list = new ArrayList<>(loadedPlugins.values());
        list.sort((a, b) -> a.spec.getName().compareToIgnoreCase(b.spec.getName()));
        return Collections.unmodifiableList(list);
    }

    /**
     * 判断某个插件是否存在。
     *
     * @param pluginName 插件名
     * @return 是否存在
     */
    public boolean has(String pluginName) {
        return loadedPlugins.containsKey(pluginName);
    }

    /**
     * 查询指定插件发现的 MCP 配置文件清单。
     *
     * <p>用途：{@code /plugin info &lt;name&gt;} 展示该插件实际贡献了哪些 MCP 配置文件。
     * 返回空列表的情形：插件未注册 / 未提供 mcp 目录 / 已 unload。
     *
     * @param pluginName 插件名
     * @return 该插件发现的配置文件路径（只读），永不为 {@code null}
     */
    public List<Path> getDiscoveredMcpConfigFiles(String pluginName) {
        return mcpModuleAdapter.getDiscoveredConfigFiles(pluginName);
    }

    /**
     * 汇总所有插件发现的 MCP 配置文件（按插件维度）。
     *
     * <p>用途：{@code JimiFactory} 在创建 Engine 时调用本方法，把所有 value 展平后
     * 一次性传给 {@code MCPToolProvider.setMcpConfigFiles}。
     *
     * @return {@code pluginName -> configFiles} 的只读快照
     */
    public Map<String, List<Path>> getAllDiscoveredMcpConfigFiles() {
        return mcpModuleAdapter.getAllDiscoveredConfigFiles();
    }

    /**
     * 获取注册中心的统计信息（用于 {@code /plugin list} 概览）。
     *
     * @return 统计信息 Map
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPlugins", loadedPlugins.size());

        long enabled = loadedPlugins.values().stream().filter(s -> s.enabled).count();
        long disabled = loadedPlugins.values().stream()
                .filter(s -> !s.enabled && s.rejectReason == null).count();
        long rejected = loadedPlugins.values().stream()
                .filter(s -> s.rejectReason != null).count();
        stats.put("enabled", enabled);
        stats.put("disabled", disabled);
        stats.put("rejected", rejected);

        Map<PluginScope, Long> byScope = new LinkedHashMap<>();
        for (PluginState state : loadedPlugins.values()) {
            byScope.merge(state.spec.getScope(), 1L, Long::sum);
        }
        stats.put("byScope", byScope);

        return stats;
    }

    /**
     * 插件的完整运行时状态。
     *
     * <p>封装三类信息：
     * <ol>
     *   <li>插件规范 {@link #spec}</li>
     *   <li>启用标志 {@link #enabled}</li>
     *   <li>加载结果 {@link #loadResult}（仅启用时非 {@code null}）</li>
     *   <li>拒绝原因 {@link #rejectReason}（兼容性校验失败时非 {@code null}）</li>
     * </ol>
     */
    public static final class PluginState {

        /** 插件规范 */
        public final PluginSpec spec;

        /** 是否启用 */
        public final boolean enabled;

        /** 加载结果（仅启用时非 null） */
        public final PluginLoadResult loadResult;

        /** 拒绝加载的原因（例如兼容性不匹配） */
        public final String rejectReason;

        private PluginState(PluginSpec spec, boolean enabled,
                            PluginLoadResult loadResult, String rejectReason) {
            this.spec = spec;
            this.enabled = enabled;
            this.loadResult = loadResult;
            this.rejectReason = rejectReason;
        }

        static PluginState enabled(PluginSpec spec, PluginLoadResult loadResult) {
            return new PluginState(spec, true, loadResult, null);
        }

        static PluginState disabled(PluginSpec spec) {
            return new PluginState(spec, false, null, null);
        }

        static PluginState rejected(PluginSpec spec, String reason) {
            return new PluginState(spec, false, null, reason);
        }
    }
}
