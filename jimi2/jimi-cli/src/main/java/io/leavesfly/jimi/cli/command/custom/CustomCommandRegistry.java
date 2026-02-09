package io.leavesfly.jimi.cli.command.custom;

import io.leavesfly.jimi.cli.command.CommandRegistry;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义命令注册管理器
 * 加载自定义命令并注册到 CommandRegistry
 */
@Slf4j
public class CustomCommandRegistry {
    
    private final CustomCommandLoader loader;
    private final CommandRegistry commandRegistry;
    private final Map<String, ConfigurableCommandHandler> customCommands = new ConcurrentHashMap<>();
    private Path projectDirectory;
    
    public CustomCommandRegistry(CustomCommandLoader loader, CommandRegistry commandRegistry) {
        this.loader = loader;
        this.commandRegistry = commandRegistry;
    }
    
    /**
     * 初始化并加载自定义命令
     */
    public void init() {
        log.info("Initializing custom command registry...");
        try {
            loader.ensureUserCommandsDirectory();
            loadAndRegisterCommands();
            log.info("Custom command registry initialized with {} commands", customCommands.size());
        } catch (Exception e) {
            log.error("Failed to initialize custom command registry", e);
        }
    }
    
    public void setProjectDirectory(Path projectDir) {
        this.projectDirectory = projectDir;
    }
    
    public void loadAndRegisterCommands() {
        List<CustomCommandSpec> specs = loader.loadAllCommands(projectDirectory);
        for (CustomCommandSpec spec : specs) {
            try {
                registerCommand(spec);
            } catch (Exception e) {
                log.error("Failed to register custom command: {}", spec.getName(), e);
            }
        }
    }
    
    public void registerCommand(CustomCommandSpec spec) {
        if (!spec.isEnabled()) {
            log.debug("Skipping disabled command: {}", spec.getName());
            return;
        }
        
        // 创建 Command 适配器
        ConfigurableCommandHandler handler = new ConfigurableCommandHandler(spec);
        customCommands.put(spec.getName(), handler);
        
        // 注册到全局 CommandRegistry
        commandRegistry.register(handler);
        
        log.info("Registered custom command: {} (category={}, priority={})", 
                spec.getName(), spec.getCategory(), spec.getPriority());
    }
    
    public void unregisterCommand(String name) {
        ConfigurableCommandHandler handler = customCommands.remove(name);
        if (handler != null) {
            commandRegistry.unregister(name);
            log.info("Unregistered custom command: {}", name);
        }
    }
    
    public List<CustomCommandSpec> getCustomCommandSpecs() {
        List<CustomCommandSpec> specs = new ArrayList<>();
        customCommands.values().forEach(h -> specs.add(h.getSpec()));
        return specs;
    }
    
    public int getCustomCommandCount() {
        return customCommands.size();
    }
    
    public void reloadCommands() {
        log.info("Reloading custom commands...");
        // 先注销所有现有自定义命令
        new ArrayList<>(customCommands.keySet()).forEach(this::unregisterCommand);
        // 重新加载
        loadAndRegisterCommands();
        log.info("Reloaded {} custom commands", customCommands.size());
    }
}
