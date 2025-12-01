package io.leavesfly.jimi.command.custom;

import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.command.CommandRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义命令注册管理器
 * 
 * 职责:
 * - 在应用启动时自动加载并注册自定义命令
 * - 管理自定义命令的生命周期
 * - 支持热加载和动态注册/注销
 * - 跟踪哪些命令是自定义的
 */
@Slf4j
@Service
public class CustomCommandRegistry {
    
    @Autowired
    @Lazy  // 延迟注入以打破与 CommandRegistry 的循环依赖
    private CommandRegistry commandRegistry;
    
    @Autowired
    private CustomCommandLoader loader;
    
    /**
     * 已注册的自定义命令跟踪
     * Key: 命令名称, Value: CustomCommandSpec
     */
    private final Map<String, CustomCommandSpec> customCommands = new ConcurrentHashMap<>();
    
    /**
     * 项目目录 (可选)
     */
    private Path projectDirectory;
    
    /**
     * 应用完全启动后自动加载自定义命令
     * 使用 ApplicationReadyEvent 确保所有 Bean 已完全初始化,避免循环依赖
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("Initializing custom command registry...");
        
        try {
            // 确保用户命令目录存在
            loader.ensureUserCommandsDirectory();
            
            // 加载并注册自定义命令
            loadAndRegisterCommands();
            
            log.info("Custom command registry initialized with {} commands", customCommands.size());
            
        } catch (Exception e) {
            log.error("Failed to initialize custom command registry", e);
        }
    }
    
    /**
     * 设置项目目录
     * 
     * @param projectDir 项目目录路径
     */
    public void setProjectDirectory(Path projectDir) {
        this.projectDirectory = projectDir;
        log.debug("Project directory set to: {}", projectDir);
    }
    
    /**
     * 加载并注册所有自定义命令
     */
    public void loadAndRegisterCommands() {
        List<CustomCommandSpec> specs = loader.loadAllCommands(projectDirectory);
        
        for (CustomCommandSpec spec : specs) {
            try {
                registerCommand(spec);
            } catch (Exception e) {
                log.error("Failed to register custom command: {}", spec.getName(), e);
            }
        }
        
        log.info("Loaded {} custom commands", customCommands.size());
    }
    
    /**
     * 注册单个自定义命令
     * 
     * @param spec 命令规范
     */
    public void registerCommand(CustomCommandSpec spec) {
        try {
            // 验证配置
            spec.validate();
            
            // 检查命令是否已存在
            if (customCommands.containsKey(spec.getName())) {
                log.warn("Custom command '{}' already registered, updating", spec.getName());
                unregisterCommand(spec.getName());
            }
            
            // 创建命令处理器
            CommandHandler handler = new ConfigurableCommandHandler(spec);
            
            // 注册到命令注册表
            commandRegistry.register(handler);
            
            // 跟踪自定义命令
            customCommands.put(spec.getName(), spec);
            
            log.info("Registered custom command: {} (category={}, source={})", 
                    spec.getName(), spec.getCategory(), spec.getConfigFilePath());
            
        } catch (Exception e) {
            log.error("Failed to register custom command: {}", spec.getName(), e);
            throw new RuntimeException("Failed to register custom command: " + spec.getName(), e);
        }
    }
    
    /**
     * 注销自定义命令
     * 
     * @param commandName 命令名称
     */
    public void unregisterCommand(String commandName) {
        CustomCommandSpec spec = customCommands.remove(commandName);
        if (spec != null) {
            commandRegistry.unregister(commandName);
            log.info("Unregistered custom command: {}", commandName);
        }
    }
    
    /**
     * 重新加载所有自定义命令
     * 
     * 先注销所有已注册的自定义命令,然后重新加载
     */
    public void reloadCommands() {
        log.info("Reloading custom commands...");
        
        // 注销所有自定义命令
        List<String> commandNames = new ArrayList<>(customCommands.keySet());
        for (String name : commandNames) {
            unregisterCommand(name);
        }
        
        // 重新加载
        loadAndRegisterCommands();
        
        log.info("Reloaded {} custom commands", customCommands.size());
    }
    
    /**
     * 检查命令是否为自定义命令
     * 
     * @param commandName 命令名称
     * @return 如果是自定义命令返回 true
     */
    public boolean isCustomCommand(String commandName) {
        return customCommands.containsKey(commandName);
    }
    
    /**
     * 获取自定义命令规范
     * 
     * @param commandName 命令名称
     * @return 命令规范,如果不存在返回 null
     */
    public CustomCommandSpec getCommandSpec(String commandName) {
        return customCommands.get(commandName);
    }
    
    /**
     * 获取所有自定义命令列表
     * 
     * @return 自定义命令规范列表
     */
    public List<CustomCommandSpec> getAllCustomCommands() {
        return new ArrayList<>(customCommands.values());
    }
    
    /**
     * 获取按分类组织的自定义命令
     * 
     * @param category 分类名称
     * @return 该分类下的自定义命令列表
     */
    public List<CustomCommandSpec> getCommandsByCategory(String category) {
        return customCommands.values().stream()
                .filter(spec -> category.equals(spec.getCategory()))
                .toList();
    }
    
    /**
     * 获取自定义命令数量
     * 
     * @return 命令数量
     */
    public int getCommandCount() {
        return customCommands.size();
    }
    
    /**
     * 启用自定义命令
     * 
     * @param commandName 命令名称
     */
    public void enableCommand(String commandName) {
        CustomCommandSpec spec = customCommands.get(commandName);
        if (spec != null && !spec.isEnabled()) {
            spec.setEnabled(true);
            log.info("Enabled custom command: {}", commandName);
        }
    }
    
    /**
     * 禁用自定义命令
     * 
     * @param commandName 命令名称
     */
    public void disableCommand(String commandName) {
        CustomCommandSpec spec = customCommands.get(commandName);
        if (spec != null && spec.isEnabled()) {
            spec.setEnabled(false);
            log.info("Disabled custom command: {}", commandName);
        }
    }
    
    /**
     * 获取用户命令目录路径
     */
    public Path getUserCommandsDirectory() {
        return loader.getUserCommandsDirectory();
    }
    
    /**
     * 获取项目命令目录路径
     */
    public Path getProjectCommandsDirectory() {
        if (projectDirectory == null) {
            return null;
        }
        return loader.getProjectCommandsDirectory(projectDirectory);
    }
}
