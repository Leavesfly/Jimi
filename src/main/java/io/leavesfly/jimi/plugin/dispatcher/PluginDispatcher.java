package io.leavesfly.jimi.plugin.dispatcher;

import io.leavesfly.jimi.plugin.spec.PluginSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件扩展点分发器
 *
 * <p>核心分发门面——聚合所有 {@link PluginModuleAdapter} 实现，把单个插件的加载请求
 * 按扩展点模块分发给对应 Adapter。
 *
 * <p>执行策略（对齐设计方案 §5）：
 * <ol>
 *   <li>遍历所有注入的 Adapter（保持 Spring 注入顺序）</li>
 *   <li>对每个 Adapter 先检查 {@link PluginSpec#isModuleEnabled} 模块开关</li>
 *   <li>再检查 {@link PluginModuleAdapter#supports} 判断目录是否存在</li>
 *   <li>捕获单 Adapter 加载异常，封装到 {@link ModuleLoadResult#failed}，不中断其他模块</li>
 * </ol>
 */
@Slf4j
@Component
public class PluginDispatcher {

    private final List<PluginModuleAdapter> adapters;

    /**
     * 由 Spring 自动注入所有 {@link PluginModuleAdapter} 实现。
     *
     * @param adapters 所有 Adapter 实现
     */
    @Autowired
    public PluginDispatcher(List<PluginModuleAdapter> adapters) {
        this.adapters = adapters == null ? Collections.emptyList() : adapters;
        log.info("PluginDispatcher initialized with {} adapter(s): {}",
                this.adapters.size(),
                this.adapters.stream().map(PluginModuleAdapter::getModuleName).toList());
    }

    /**
     * 把单个插件分发到各 Adapter 加载。
     *
     * @param spec 插件规范（需已填充 {@code pluginDir} / {@code scope}）
     * @return 聚合加载结果
     */
    public PluginLoadResult dispatch(PluginSpec spec) {
        Path pluginDir = spec.getPluginDir();
        if (pluginDir == null) {
            log.warn("Plugin '{}' has null pluginDir, skipping dispatch", spec.getName());
            return new PluginLoadResult(spec, Collections.emptyMap());
        }

        Map<String, ModuleLoadResult> results = new LinkedHashMap<>();

        for (PluginModuleAdapter adapter : adapters) {
            String moduleName = adapter.getModuleName();

            if (!spec.isModuleEnabled(moduleName)) {
                log.debug("Plugin '{}' module '{}' disabled by spec, skipping",
                        spec.getName(), moduleName);
                continue;
            }

            if (!adapter.supports(pluginDir)) {
                log.debug("Plugin '{}' has no '{}' directory, skipping",
                        spec.getName(), moduleName);
                continue;
            }

            try {
                ModuleLoadResult result = adapter.load(pluginDir, spec);
                results.put(moduleName, result);

                if (result.isSuccess()) {
                    log.info("Plugin '{}' module '{}' loaded {} item(s): {}",
                            spec.getName(), moduleName,
                            result.getLoadedItems().size(), result.getLoadedItems());
                } else {
                    log.error("Plugin '{}' module '{}' failed to load: {}",
                            spec.getName(), moduleName, result.getErrorMessage());
                }
            } catch (Exception e) {
                log.error("Plugin '{}' module '{}' threw unexpected exception: {}",
                        spec.getName(), moduleName, e.getMessage(), e);
                results.put(moduleName, ModuleLoadResult.failed(e));
            }
        }

        return new PluginLoadResult(spec, results);
    }

    /**
     * 把单个插件的所有已加载模块反向卸载。
     *
     * <p>幂等：缺失对应 Adapter 或模块未加载时安全跳过。
     *
     * @param previousResult 之前 {@link #dispatch} 返回的结果
     */
    public void unload(PluginLoadResult previousResult) {
        if (previousResult == null) {
            return;
        }
        PluginSpec spec = previousResult.getSpec();
        Map<String, ModuleLoadResult> results = previousResult.getModuleResults();

        for (PluginModuleAdapter adapter : adapters) {
            String moduleName = adapter.getModuleName();
            ModuleLoadResult moduleResult = results.get(moduleName);
            if (moduleResult == null || !moduleResult.isSuccess()) {
                continue;
            }
            try {
                adapter.unload(spec, moduleResult);
                log.info("Plugin '{}' module '{}' unloaded {} item(s)",
                        spec.getName(), moduleName, moduleResult.getLoadedItems().size());
            } catch (Exception e) {
                log.warn("Plugin '{}' module '{}' unload failed: {}",
                        spec.getName(), moduleName, e.getMessage());
            }
        }
    }

    /**
     * 获取所有已注入的 Adapter 列表（只读）。
     *
     * @return Adapter 列表
     */
    public List<PluginModuleAdapter> getAdapters() {
        return Collections.unmodifiableList(adapters);
    }
}
