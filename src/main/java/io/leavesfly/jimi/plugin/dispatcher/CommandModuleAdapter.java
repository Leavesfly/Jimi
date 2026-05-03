package io.leavesfly.jimi.plugin.dispatcher;

import io.leavesfly.jimi.command.custom.CustomCommandRegistry;
import io.leavesfly.jimi.command.custom.CustomCommandSpec;
import io.leavesfly.jimi.common.YamlConfigLoader;
import io.leavesfly.jimi.plugin.spec.PluginSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Commands 扩展点适配器
 *
 * <p>把插件目录下 {@code commands/} 子目录中的 {@code *.yaml} 自定义命令配置桥接到
 * {@link CustomCommandRegistry}。
 *
 * <p>加载流程：
 * <ol>
 *   <li>确认 {@code &lt;plugin&gt;/commands/} 子目录存在</li>
 *   <li>使用 {@link YamlConfigLoader#loadFromDirectory} 直接按目录扫描</li>
 *   <li>对每个 {@link CustomCommandSpec} 调 {@link CustomCommandSpec#validate()} 校验</li>
 *   <li>按 {@link PluginSpec#getProvides()} 白名单过滤</li>
 *   <li>逐个调 {@link CustomCommandRegistry#registerCommand(CustomCommandSpec)} 注册</li>
 * </ol>
 *
 * <p>注意：{@link CustomCommandRegistry#registerCommand} 内部已处理同名覆盖，
 * 并会根据 {@link CustomCommandSpec#isEnabled()} 决定是否真实注册到全局
 * {@code CommandRegistry}。
 */
@Slf4j
@Component
public class CommandModuleAdapter implements PluginModuleAdapter {

    /** 子目录名约定 */
    public static final String SUBDIR = "commands";

    @Autowired
    private YamlConfigLoader yamlConfigLoader;

    @Autowired
    private CustomCommandRegistry customCommandRegistry;

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
        Path commandsDir = pluginDir.resolve(SUBDIR);
        String source = "plugin:" + spec.getName();

        try {
            List<CustomCommandSpec> loaded = yamlConfigLoader.loadFromDirectory(
                    commandsDir, CustomCommandSpec.class, source);
            List<String> registered = new ArrayList<>();

            for (CustomCommandSpec cmd : loaded) {
                String commandName = cmd.getName();
                if (commandName == null || commandName.isBlank()) {
                    log.warn("Plugin '{}' skipping command without name", spec.getName());
                    continue;
                }
                if (!spec.getProvides().allowsCommand(commandName)) {
                    log.debug("Plugin '{}' command '{}' filtered by provides whitelist",
                            spec.getName(), commandName);
                    continue;
                }
                try {
                    cmd.validate();
                    customCommandRegistry.registerCommand(cmd);
                    registered.add(commandName);
                } catch (Exception e) {
                    log.error("Plugin '{}' command '{}' failed to register: {}",
                            spec.getName(), commandName, e.getMessage());
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
        for (String commandName : previousResult.getLoadedItems()) {
            try {
                customCommandRegistry.unregisterCommand(commandName);
            } catch (Exception e) {
                log.warn("Plugin '{}' failed to unregister command '{}': {}",
                        spec.getName(), commandName, e.getMessage());
            }
        }
        log.info("Plugin '{}' unloaded {} command(s)",
                spec.getName(), previousResult.getLoadedItems().size());
    }
}
