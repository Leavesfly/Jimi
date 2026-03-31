package io.leavesfly.jimi.core.hook;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Hook 注册管理器
 * 
 * 职责:
 * - 管理所有 Hook 的注册和生命周期
 * - 根据 Hook 类型组织 Hook
 * - 触发 Hook 执行
 * - 支持热加载和动态注册/注销
 */
@Slf4j
@Service
public class HookRegistry {
    
    @Autowired
    private HookLoader loader;
    
    @Autowired
    private HookExecutor executor;
    
    /**
     * 按类型组织的 Hook 映射
     * Key: HookType, Value: Hook 列表(按优先级排序)
     */
    private final Map<HookType, List<HookSpec>> hooksByType = new ConcurrentHashMap<>();
    
    /**
     * 所有注册的 Hook
     * Key: Hook 名称, Value: HookSpec
     */
    private final Map<String, HookSpec> allHooks = new ConcurrentHashMap<>();
    
    /**
     * 项目目录 (可选)
     */
    private Path projectDirectory;
    
    /**
     * 应用启动时自动加载 Hooks
     */
    @PostConstruct
    public void init() {
        log.info("Initializing hook registry...");
        
        try {
            // 确保用户 Hook 目录存在
            loader.ensureUserHooksDirectory();
            
            // 加载并注册 Hooks
            loadAndRegisterHooks();
            
            log.info("Hook registry initialized with {} hooks", allHooks.size());
            
        } catch (Exception e) {
            log.error("Failed to initialize hook registry", e);
        }
    }
    
    /**
     * 设置项目目录
     */
    public void setProjectDirectory(Path projectDir) {
        this.projectDirectory = projectDir;
        log.debug("Project directory set to: {}", projectDir);
    }
    
    /**
     * 加载并注册所有 Hooks
     */
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
    
    /**
     * 注册单个 Hook
     */
    public void registerHook(HookSpec spec) {
        try {
            // 验证配置
            spec.validate();
            
            // 检查是否已存在
            if (allHooks.containsKey(spec.getName())) {
                log.warn("Hook '{}' already registered, updating", spec.getName());
                unregisterHook(spec.getName());
            }
            
            // 添加到总列表
            allHooks.put(spec.getName(), spec);
            
            // 按类型组织
            HookType type = spec.getTrigger().getType();
            hooksByType.computeIfAbsent(type, k -> new ArrayList<>()).add(spec);
            
            // 按优先级排序
            hooksByType.get(type).sort(
                Comparator.comparingInt(HookSpec::getPriority).reversed()
            );
            
            log.info("Registered hook: {} (type={}, priority={}, source={})", 
                    spec.getName(), type, spec.getPriority(), spec.getConfigFilePath());
            
        } catch (Exception e) {
            log.error("Failed to register hook: {}", spec.getName(), e);
            throw new RuntimeException("Failed to register hook: " + spec.getName(), e);
        }
    }
    
    /**
     * 注销 Hook
     */
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
     * 
     * @param type Hook 类型
     * @param context Hook 上下文
     * @return 异步执行结果
     */
    public Mono<Void> trigger(HookType type, HookContext context) {
        List<HookSpec> hooks = getHooks(type);
        
        if (hooks.isEmpty()) {
            log.debug("No hooks registered for type: {}", type);
            return Mono.empty();
        }
        
        log.debug("Triggering {} hooks for type: {}", hooks.size(), type);
        
        // 过滤匹配的 Hook
        List<HookSpec> matchedHooks = hooks.stream()
                .filter(HookSpec::isEnabled)
                .filter(hook -> matches(hook, context))
                .toList();
        
        if (matchedHooks.isEmpty()) {
            log.debug("No matching hooks for type: {}", type);
            return Mono.empty();
        }
        
        log.info("Executing {} matched hooks for type: {}", matchedHooks.size(), type);
        
        // 依次执行所有匹配的 Hook (按优先级顺序，非阻塞)
        return Flux.fromIterable(matchedHooks)
                .concatMap(hook -> executor.execute(hook, context)
                        .onErrorResume(e -> {
                            log.error("Hook execution failed: {}", hook.getName(), e);
                            return Mono.empty();
                        }))
                .then();
    }
    
    /**
     * 检查 Hook 是否匹配当前上下文
     *
     * 支持两种匹配方式（对齐 Claude Code）：
     * 1. matcher 正则表达式匹配（优先）
     * 2. tools 列表匹配（兼容 YAML 配置）
     */
    private boolean matches(HookSpec hook, HookContext context) {
        HookTrigger trigger = hook.getTrigger();

        // 优先使用 matcher 正则匹配（对齐 Claude Code）
        if (trigger.getMatcher() != null && !trigger.getMatcher().isEmpty()) {
            String matchTarget = resolveMatchTarget(trigger.getType(), context);
            if (!trigger.matchesValue(matchTarget)) {
                return false;
            }
        }

        // 检查工具名称列表匹配（兼容 YAML 配置）
        if (trigger.getTools() != null && !trigger.getTools().isEmpty()) {
            if (context.getToolName() == null ||
                !trigger.getTools().contains(context.getToolName())) {
                return false;
            }
        }

        // 检查文件模式匹配
        if (trigger.getFilePatterns() != null && !trigger.getFilePatterns().isEmpty()) {
            if (context.getAffectedFiles() == null || context.getAffectedFiles().isEmpty()) {
                return false;
            }

            boolean fileMatched = context.getAffectedFiles().stream()
                    .anyMatch(file -> matchesAnyPattern(file, trigger.getFilePatterns()));

            if (!fileMatched) {
                return false;
            }
        }

        // 检查 Agent 名称匹配
        if (trigger.getAgentName() != null) {
            if (!trigger.getAgentName().equals(context.getAgentName())) {
                return false;
            }
        }

        // 检查错误模式匹配
        if (trigger.getErrorPattern() != null) {
            if (context.getErrorMessage() == null ||
                !context.getErrorMessage().matches(trigger.getErrorPattern())) {
                return false;
            }
        }

        return true;
    }

    /**
     * 根据 Hook 类型确定 matcher 的匹配目标
     */
    private String resolveMatchTarget(HookType type, HookContext context) {
        return switch (type) {
            case PRE_TOOL_USE, POST_TOOL_USE, POST_TOOL_USE_FAILURE -> context.getToolName();
            case PRE_AGENT_SWITCH, POST_AGENT_SWITCH, SUBAGENT_STOP -> context.getAgentName();
            case NOTIFICATION -> context.getNotificationType();
            case ON_ERROR -> context.getErrorMessage();
            default -> null;
        };
    }
    
    /**
     * 检查文件是否匹配任意模式
     */
    private boolean matchesAnyPattern(Path file, List<String> patterns) {
        String fileName = file.getFileName().toString();
        return patterns.stream()
                .anyMatch(pattern -> matchesPattern(fileName, pattern));
    }
    
    /**
     * 简单的 glob 模式匹配
     */
    private boolean matchesPattern(String fileName, String pattern) {
        // 简化的 glob 匹配,支持 * 和 ?
        String regex = pattern.replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return fileName.matches(regex);
    }
    
    /**
     * 获取指定类型的所有 Hook
     */
    public List<HookSpec> getHooks(HookType type) {
        return hooksByType.getOrDefault(type, Collections.emptyList());
    }
    
    /**
     * 获取所有 Hook
     */
    public List<HookSpec> getAllHooks() {
        return new ArrayList<>(allHooks.values());
    }
    
    /**
     * 获取 Hook 规范
     */
    public HookSpec getHook(String name) {
        return allHooks.get(name);
    }
    
    /**
     * 检查 Hook 是否存在
     */
    public boolean hasHook(String name) {
        return allHooks.containsKey(name);
    }
    
    /**
     * 获取 Hook 数量
     */
    public int getHookCount() {
        return allHooks.size();
    }
    
    /**
     * 启用 Hook
     */
    public void enableHook(String name) {
        HookSpec hook = allHooks.get(name);
        if (hook != null) {
            hook.setEnabled(true);
            log.info("Enabled hook: {}", name);
        }
    }
    
    /**
     * 禁用 Hook
     */
    public void disableHook(String name) {
        HookSpec hook = allHooks.get(name);
        if (hook != null) {
            hook.setEnabled(false);
            log.info("Disabled hook: {}", name);
        }
    }
    
    /**
     * 重新加载所有 Hooks
     */
    public void reloadHooks() {
        log.info("Reloading hooks...");
        
        // 清空现有 Hook
        allHooks.clear();
        hooksByType.clear();
        
        // 重新加载
        loadAndRegisterHooks();
        
        log.info("Reloaded {} hooks", allHooks.size());
    }
    
    /**
     * 获取按类型分组的 Hook 统计
     */
    public Map<HookType, Integer> getHookStatistics() {
        return hooksByType.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().size()
                ));
    }
}
