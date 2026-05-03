package io.leavesfly.jimi.plugin.dispatcher;

import io.leavesfly.jimi.plugin.spec.PluginSpec;

import java.nio.file.Path;

/**
 * 插件扩展点模块适配器
 *
 * <p>每种扩展点（Skills / Hooks / Commands / MCP / Agents）对应一个独立的 Adapter 实现，
 * 负责把插件目录下特定子目录的内容桥接到 Jimi 原生的 Registry 上。
 *
 * <p>实现类应当是 Spring {@code @Component}，由 {@link PluginDispatcher}
 * 通过 {@code List&lt;PluginModuleAdapter&gt;} 自动注入并分发。
 *
 * <h3>实现约定</h3>
 * <ol>
 *   <li><b>零侵入</b>：不改写现有 Registry 的已有方法签名，仅复用其公开 API</li>
 *   <li><b>白名单过滤</b>：注册前必须检查 {@link PluginSpec#getProvides()} 白名单</li>
 *   <li><b>异常隔离</b>：单项加载失败应 {@code log.warn} 但不中断整个模块加载</li>
 *   <li><b>可逆</b>：{@link #unload} 必须与 {@link #load} 对称，能完整回退注册动作</li>
 * </ol>
 *
 * @see SkillModuleAdapter Skills 模块实现
 * @see HookModuleAdapter Hooks 模块实现
 * @see CommandModuleAdapter Commands 模块实现
 */
public interface PluginModuleAdapter {

    /**
     * 获取模块名
     *
     * <p>必须与 {@link io.leavesfly.jimi.plugin.spec.PluginModuleToggle#isModuleEnabled} 识别的
     * 模块名对齐（{@code skills} / {@code hooks} / {@code commands} / {@code mcp} / {@code agents}）。
     *
     * @return 模块名（小写）
     */
    String getModuleName();

    /**
     * 判断当前插件目录是否包含本模块需要处理的内容
     *
     * <p>实现通常是检查插件根目录下对应子目录（如 {@code skills/}）是否存在。
     * 返回 {@code false} 时 {@link PluginDispatcher} 会直接跳过该模块的加载。
     *
     * @param pluginDir 插件根目录
     * @return 是否需要处理
     */
    boolean supports(Path pluginDir);

    /**
     * 加载并注册插件中本模块的所有扩展项
     *
     * <p>实现需要：
     * <ol>
     *   <li>扫描插件目录下的对应子目录</li>
     *   <li>解析每个扩展项，过滤白名单外的</li>
     *   <li>通过原生 Registry 的公开 API 注册</li>
     *   <li>在 {@link ModuleLoadResult} 中记录实际注册的扩展项名</li>
     * </ol>
     *
     * @param pluginDir 插件根目录
     * @param spec      插件规范
     * @return 加载结果；实现不应抛异常，应封装到 {@link ModuleLoadResult#failed}
     */
    ModuleLoadResult load(Path pluginDir, PluginSpec spec);

    /**
     * 卸载插件中本模块已加载的所有扩展项
     *
     * <p>根据 {@link ModuleLoadResult#getLoadedItems()} 记录的扩展项名，逐个从原生 Registry
     * 注销。实现应当幂等——即便同一项已被卸载也不报错。
     *
     * @param spec              插件规范
     * @param previousResult    {@link #load} 返回的结果（用于反查已加载项）
     */
    void unload(PluginSpec spec, ModuleLoadResult previousResult);
}
