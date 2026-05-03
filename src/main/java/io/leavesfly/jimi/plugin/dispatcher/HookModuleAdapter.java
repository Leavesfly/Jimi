package io.leavesfly.jimi.plugin.dispatcher;

import io.leavesfly.jimi.common.YamlConfigLoader;
import io.leavesfly.jimi.core.hook.HookRegistry;
import io.leavesfly.jimi.core.hook.HookSpec;
import io.leavesfly.jimi.plugin.spec.PluginSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hooks 扩展点适配器
 *
 * <p>把插件目录下 {@code hooks/} 子目录中的 {@code *.yaml} Hook 配置桥接到
 * {@link HookRegistry}。
 *
 * <p>加载流程：
 * <ol>
 *   <li>确认 {@code &lt;plugin&gt;/hooks/} 子目录存在</li>
 *   <li>使用 {@link YamlConfigLoader#loadFromDirectory} 直接按目录扫描</li>
 *   <li>对每个 {@link HookSpec} 调用 {@link HookSpec#validate()} 校验</li>
 *   <li>按 {@link PluginSpec#getProvides()} 白名单过滤</li>
 *   <li>逐个调 {@link HookRegistry#registerHook(HookSpec)} 注册</li>
 * </ol>
 *
 * <p>注意：{@link HookRegistry#registerHook} 遇到同名 Hook 会自动先 unregister 再注册，
 * 天然支持"后加载覆盖"语义。
 */
@Slf4j
@Component
public class HookModuleAdapter implements PluginModuleAdapter {

    /** 子目录名约定 */
    public static final String SUBDIR = "hooks";

    @Autowired
    private YamlConfigLoader yamlConfigLoader;

    @Autowired
    private HookRegistry hookRegistry;

    @Override
    public String getModuleName() {
        return SUBDIR;
    }

    @Override
    public boolean supports(Path pluginDir) {
        Path subDir = pluginDir.resolve(SUBDIR);
        return Files.isDirectory(subDir);
    }

    @Override
    public ModuleLoadResult load(Path pluginDir, PluginSpec spec) {
        Path hooksDir = pluginDir.resolve(SUBDIR);
        String source = "plugin:" + spec.getName();

        try {
            List<HookSpec> loaded = yamlConfigLoader.loadFromDirectory(
                    hooksDir, HookSpec.class, source);
            List<String> registered = new ArrayList<>();

            for (HookSpec hook : loaded) {
                String hookName = hook.getName();
                if (hookName == null || hookName.isBlank()) {
                    log.warn("Plugin '{}' skipping hook without name", spec.getName());
                    continue;
                }
                if (!spec.getProvides().allowsHook(hookName)) {
                    log.debug("Plugin '{}' hook '{}' filtered by provides whitelist",
                            spec.getName(), hookName);
                    continue;
                }
                try {
                    hook.validate();
                    hookRegistry.registerHook(hook);
                    registered.add(hookName);
                } catch (Exception e) {
                    log.error("Plugin '{}' hook '{}' failed to register: {}",
                            spec.getName(), hookName, e.getMessage());
                }
            }

            return ModuleLoadResult.success(registered);
        } catch (Exception e) {
            return ModuleLoadResult.failed(e);
        }
    }

    @Override
    public void unload(PluginSpec spec, ModuleLoadResult previousResult) {
        if (previousResult == null || previousResult.getLoadedItems().isEmpty()) {
            return;
        }
        for (String hookName : previousResult.getLoadedItems()) {
            try {
                hookRegistry.unregisterHook(hookName);
            } catch (Exception e) {
                log.warn("Plugin '{}' failed to unregister hook '{}': {}",
                        spec.getName(), hookName, e.getMessage());
            }
        }
        log.info("Plugin '{}' unloaded {} hook(s)",
                spec.getName(), previousResult.getLoadedItems().size());
    }
}
