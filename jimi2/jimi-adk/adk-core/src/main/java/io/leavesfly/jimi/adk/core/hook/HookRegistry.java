package io.leavesfly.jimi.adk.core.hook;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Hook 注册管理器
 * 管理所有 Hook 的注册、生命周期和触发
 */
@Slf4j
public class HookRegistry {
    
    private final HookLoader loader;
    private final HookExecutor executor;
    
    /** 按类型组织的 Hook 映射 */
    private final Map<HookType, List<HookSpec>> hooksByType = new ConcurrentHashMap<>();
    
    /** 所有注册的 Hook */
    private final Map<String, HookSpec> allHooks = new ConcurrentHashMap<>();
    
    private Path projectDirectory;
    
    public HookRegistry(HookLoader loader, HookExecutor executor) {
        this.loader = loader;
        this.executor = executor;
    }
    
    /**
     * 初始化 - 需要调用方手动触发（替代 @PostConstruct）
     */
    public void init() {
        log.info("Initializing hook registry...");
        try {
            loader.ensureUserHooksDirectory();
            loadAndRegisterHooks();
            log.info("Hook registry initialized with {} hooks", allHooks.size());
        } catch (Exception e) {
            log.error("Failed to initialize hook registry", e);
        }
    }
    
    public void setProjectDirectory(Path projectDir) {
        this.projectDirectory = projectDir;
        log.debug("Project directory set to: {}", projectDir);
    }
    
    public void loadAndRegisterHooks() {
        List<HookSpec> specs = loader.loadAllHooks(projectDirectory);
        for (HookSpec spec : specs) {
            try {
                registerHook(spec);
            } catch (Exception e) {
                log.error("Failed to register hook: {}", spec.getName(), e);
            }
        }
        log.info("Loaded {} hooks", allHooks.size());
    }
    
    public void registerHook(HookSpec spec) {
        try {
            spec.validate();
            if (allHooks.containsKey(spec.getName())) {
                log.warn("Hook '{}' already registered, updating", spec.getName());
                unregisterHook(spec.getName());
            }
            allHooks.put(spec.getName(), spec);
            HookType type = spec.getTrigger().getType();
            hooksByType.computeIfAbsent(type, k -> new ArrayList<>()).add(spec);
            hooksByType.get(type).sort(Comparator.comparingInt(HookSpec::getPriority).reversed());
            log.info("Registered hook: {} (type={}, priority={}, source={})", 
                    spec.getName(), type, spec.getPriority(), spec.getConfigFilePath());
        } catch (Exception e) {
            log.error("Failed to register hook: {}", spec.getName(), e);
            throw new RuntimeException("Failed to register hook: " + spec.getName(), e);
        }
    }
    
    public void unregisterHook(String hookName) {
        HookSpec spec = allHooks.remove(hookName);
        if (spec != null) {
            HookType type = spec.getTrigger().getType();
            List<HookSpec> hooks = hooksByType.get(type);
            if (hooks != null) {
                hooks.remove(spec);
            }
            log.info("Unregistered hook: {}", hookName);
        }
    }
    
    /**
     * 触发指定类型的所有 Hook
     */
    public Mono<Void> trigger(HookType type, HookContext context) {
        List<HookSpec> hooks = getHooks(type);
        if (hooks.isEmpty()) {
            return Mono.empty();
        }
        
        List<HookSpec> matchedHooks = hooks.stream()
                .filter(HookSpec::isEnabled)
                .filter(hook -> matches(hook, context))
                .toList();
        
        if (matchedHooks.isEmpty()) {
            return Mono.empty();
        }
        
        log.info("Executing {} matched hooks for type: {}", matchedHooks.size(), type);
        
        return Mono.fromRunnable(() -> {
            for (HookSpec hook : matchedHooks) {
                try {
                    executor.execute(hook, context).block();
                } catch (Exception e) {
                    log.error("Hook execution failed: {}", hook.getName(), e);
                }
            }
        });
    }
    
    private boolean matches(HookSpec hook, HookContext context) {
        HookTrigger trigger = hook.getTrigger();
        
        if (!trigger.getTools().isEmpty()) {
            if (context.getToolName() == null || 
                !trigger.getTools().contains(context.getToolName())) {
                return false;
            }
        }
        
        if (!trigger.getFilePatterns().isEmpty()) {
            if (context.getAffectedFiles().isEmpty()) {
                return false;
            }
            boolean fileMatched = context.getAffectedFiles().stream()
                    .anyMatch(file -> matchesAnyPattern(file, trigger.getFilePatterns()));
            if (!fileMatched) {
                return false;
            }
        }
        
        if (trigger.getAgentName() != null) {
            if (!trigger.getAgentName().equals(context.getAgentName())) {
                return false;
            }
        }
        
        if (trigger.getErrorPattern() != null) {
            if (context.getErrorMessage() == null || 
                !context.getErrorMessage().matches(trigger.getErrorPattern())) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean matchesAnyPattern(Path file, List<String> patterns) {
        String fileName = file.getFileName().toString();
        return patterns.stream().anyMatch(pattern -> matchesPattern(fileName, pattern));
    }
    
    private boolean matchesPattern(String fileName, String pattern) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return fileName.matches(regex);
    }
    
    public List<HookSpec> getHooks(HookType type) {
        return hooksByType.getOrDefault(type, Collections.emptyList());
    }
    
    public List<HookSpec> getAllHooks() {
        return new ArrayList<>(allHooks.values());
    }
    
    public HookSpec getHook(String name) {
        return allHooks.get(name);
    }
    
    public boolean hasHook(String name) {
        return allHooks.containsKey(name);
    }
    
    public int getHookCount() {
        return allHooks.size();
    }
    
    public void enableHook(String name) {
        HookSpec hook = allHooks.get(name);
        if (hook != null) {
            hook.setEnabled(true);
            log.info("Enabled hook: {}", name);
        }
    }
    
    public void disableHook(String name) {
        HookSpec hook = allHooks.get(name);
        if (hook != null) {
            hook.setEnabled(false);
            log.info("Disabled hook: {}", name);
        }
    }
    
    public void reloadHooks() {
        log.info("Reloading hooks...");
        allHooks.clear();
        hooksByType.clear();
        loadAndRegisterHooks();
        log.info("Reloaded {} hooks", allHooks.size());
    }
    
    public Map<HookType, Integer> getHookStatistics() {
        return hooksByType.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()));
    }
}
