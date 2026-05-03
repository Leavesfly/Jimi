package io.leavesfly.jimi.plugin.dispatcher;

import io.leavesfly.jimi.plugin.spec.PluginSpec;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个插件的完整加载结果
 *
 * <p>由 {@link PluginDispatcher#dispatch} 返回，聚合该插件在
 * 所有扩展点模块上的加载结果（每个模块对应一条 {@link ModuleLoadResult}）。
 *
 * <p>字段 {@link #moduleResults} 的 key 为模块名（{@code skills} / {@code hooks} /
 * {@code commands} / {@code mcp} / {@code agents}），value 为对应的加载结果。
 * 使用 {@link LinkedHashMap} 保证模块顺序与 Dispatcher 的注入顺序一致。
 */
@Getter
public final class PluginLoadResult {

    /**
     * 对应的插件规范
     */
    private final PluginSpec spec;

    /**
     * 各模块的加载结果（按模块名索引，保持插入顺序）
     */
    private final Map<String, ModuleLoadResult> moduleResults;

    /**
     * 构造加载结果。
     *
     * @param spec          插件规范
     * @param moduleResults 各模块的加载结果（Map 的顺序会被保留）
     */
    public PluginLoadResult(PluginSpec spec, Map<String, ModuleLoadResult> moduleResults) {
        this.spec = spec;
        this.moduleResults = moduleResults == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(moduleResults);
    }

    /**
     * 判断插件整体是否加载成功（所有模块均成功才返回 {@code true}）。
     *
     * @return 是否全成功
     */
    public boolean isAllSuccess() {
        return moduleResults.values().stream().allMatch(ModuleLoadResult::isSuccess);
    }

    /**
     * 判断是否至少有一个模块成功加载。
     *
     * @return 是否至少有一个模块成功
     */
    public boolean hasAnySuccess() {
        return moduleResults.values().stream().anyMatch(ModuleLoadResult::isSuccess);
    }
}
