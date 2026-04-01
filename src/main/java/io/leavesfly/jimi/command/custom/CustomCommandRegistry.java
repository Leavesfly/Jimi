package io.leavesfly.jimi.command.custom;

import io.leavesfly.jimi.command.CommandRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义命令注册表
 *
 * 管理所有自定义命令的生命周期：加载、注册、查找、启用/禁用。
 * 在初始化时自动加载所有自定义命令，并将其注册到全局 CommandRegistry 中。
 */
@Slf4j
@Component
public class CustomCommandRegistry {

    @Autowired
    private CustomCommandLoader commandLoader;

    @Autowired
    private CommandRegistry commandRegistry;

    private final Map<String, CustomCommandSpec> commandSpecs = new ConcurrentHashMap<>();
    private final Map<String, ConfigurableCommandHandler> commandHandlers = new ConcurrentHashMap<>();

    /**
     * 项目目录（可选，设置后会加载项目级命令）
     */
    private Path projectDirectory;

    /**
     * 初始化时加载内置和用户级自定义命令
     */
    @PostConstruct
    public void initialize() {
        log.info("Initializing custom command registry...");
        try {
            commandLoader.ensureUserCommandsDirectory();
            loadAndRegisterCommands(null);
            log.info("Custom command registry initialized with {} commands", commandSpecs.size());
        } catch (Exception e) {
            log.error("Failed to initialize custom command registry", e);
        }
    }

    /**
     * 设置项目目录并重新加载命令（包含项目级命令）
     *
     * @param projectDir 项目目录
     */
    public void setProjectDirectory(Path projectDir) {
        this.projectDirectory = projectDir;
        log.debug("Project directory set to: {}", projectDir);
        reloadCommands(projectDir);
    }

    /**
     * 加载并注册所有自定义命令
     *
     * @param projectDir 项目目录（可选）
     */
    public void loadAndRegisterCommands(Path projectDir) {
        List<CustomCommandSpec> specs = commandLoader.loadAllCommands(projectDir);

        for (CustomCommandSpec spec : specs) {
            registerCommand(spec);
        }

        log.info("Registered {} custom commands", commandSpecs.size());
    }

    /**
     * 注册单个自定义命令
     *
     * @param spec 命令规范
     */
    public void registerCommand(CustomCommandSpec spec) {
        String name = spec.getName().toLowerCase();

        if (commandSpecs.containsKey(name)) {
            log.debug("Overwriting existing custom command: {}", name);
            unregisterCommand(name);
        }

        commandSpecs.put(name, spec);

        if (spec.isEnabled()) {
            ConfigurableCommandHandler handler = new ConfigurableCommandHandler(spec);
            commandHandlers.put(name, handler);
            commandRegistry.register(handler);
            log.debug("Registered custom command: {} (category={}, type={})",
                    name, spec.getCategory(), spec.getExecutionTypeName());
        }
    }

    /**
     * 注销自定义命令
     *
     * @param commandName 命令名称
     */
    public void unregisterCommand(String commandName) {
        String name = commandName.toLowerCase();
        commandSpecs.remove(name);

        ConfigurableCommandHandler handler = commandHandlers.remove(name);
        if (handler != null) {
            commandRegistry.unregister(name);
        }
    }

    /**
     * 启用命令
     *
     * @param commandName 命令名称
     * @return 操作是否成功
     */
    public boolean enableCommand(String commandName) {
        String name = commandName.toLowerCase();
        CustomCommandSpec spec = commandSpecs.get(name);

        if (spec == null) {
            return false;
        }

        spec.setEnabled(true);

        if (!commandHandlers.containsKey(name)) {
            ConfigurableCommandHandler handler = new ConfigurableCommandHandler(spec);
            commandHandlers.put(name, handler);
            commandRegistry.register(handler);
        }

        log.info("Enabled custom command: {}", name);
        return true;
    }

    /**
     * 禁用命令
     *
     * @param commandName 命令名称
     * @return 操作是否成功
     */
    public boolean disableCommand(String commandName) {
        String name = commandName.toLowerCase();
        CustomCommandSpec spec = commandSpecs.get(name);

        if (spec == null) {
            return false;
        }

        spec.setEnabled(false);

        ConfigurableCommandHandler handler = commandHandlers.remove(name);
        if (handler != null) {
            commandRegistry.unregister(name);
        }

        log.info("Disabled custom command: {}", name);
        return true;
    }

    /**
     * 重新加载所有自定义命令
     *
     * @param projectDir 项目目录（可选，为 null 时使用已设置的项目目录）
     */
    public void reloadCommands(Path projectDir) {
        if (projectDir != null) {
            this.projectDirectory = projectDir;
        }

        List<String> existingNames = new ArrayList<>(commandSpecs.keySet());
        for (String name : existingNames) {
            unregisterCommand(name);
        }

        loadAndRegisterCommands(this.projectDirectory);
    }

    /**
     * 获取命令规范
     *
     * @param commandName 命令名称
     * @return 命令规范，不存在则返回 null
     */
    public CustomCommandSpec getCommandSpec(String commandName) {
        return commandSpecs.get(commandName.toLowerCase());
    }

    /**
     * 获取所有自定义命令规范
     *
     * @return 命令规范列表（按名称排序）
     */
    public List<CustomCommandSpec> getAllCustomCommands() {
        List<CustomCommandSpec> result = new ArrayList<>(commandSpecs.values());
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return Collections.unmodifiableList(result);
    }

    /**
     * 获取自定义命令数量
     *
     * @return 命令数量
     */
    public int size() {
        return commandSpecs.size();
    }
}
