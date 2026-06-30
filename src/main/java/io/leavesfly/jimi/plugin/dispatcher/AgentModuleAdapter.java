package io.leavesfly.jimi.plugin.dispatcher;

import io.leavesfly.jimi.core.agent.AgentRegistry;
import io.leavesfly.jimi.plugin.spec.PluginSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Agents 扩展点适配器
 *
 * <p>扫描 {@code <plugin>/agents/} 目录下的所有 {@code agent.yaml} 文件（递归），
 * 解析并注册到 {@link AgentRegistry}，使插件贡献的 Agent 在当前会话即时生效。
 *
 * <p><b>白名单行为</b>：按 {@link PluginSpec#getProvides()} 的 {@code agents}
 * 过滤，若为空则放行所有发现项。
 *
 * <p><b>可逆性</b>：{@link #unload} 通过 {@link AgentRegistry#unregisterAgentSpec(Path)}
 * 从 specCache 中驱逐对应条目，与 {@link #load} 对称。
 *
 * <p>使用 {@link Lazy} 注入 {@link AgentRegistry}，避免启动期循环依赖。
 */
@Slf4j
@Component
public class AgentModuleAdapter implements PluginModuleAdapter {

    /** 模块名（对应 plugin.yaml 里的 defaults.modules.agents） */
    public static final String MODULE_NAME = "agents";

    /** Agent 子目录名 */
    private static final String AGENTS_SUBDIR = "agents";

    /** Agent 定义文件名（Jimi 原生格式） */
    private static final String AGENT_YAML = "agent.yaml";

    /** Agent 定义文件名（Claude Code 格式） */
    private static final String AGENT_MD = "agent.md";

    @Lazy
    @Autowired
    private AgentRegistry agentRegistry;

    /**
     * 每个插件已注册的 agent.yaml 文件清单。
     *
     * <p>Key：插件名；Value：该插件 {@code agents/} 目录下实际被加载的 {@code agent.yaml}
     * 文件的绝对路径列表。用于 {@link #unload} 时精确反注册。
     *
     * <p>使用 {@link ConcurrentHashMap} 保证并发安全——reload 时可能并发 load/unload。
     */
    private final Map<String, List<Path>> registeredAgentFilesByPlugin = new ConcurrentHashMap<>();

    @Override
    public String getModuleName() {
        return MODULE_NAME;
    }

    @Override
    public boolean supports(Path pluginDir) {
        if (pluginDir == null) {
            return false;
        }
        Path agentsDir = pluginDir.resolve(AGENTS_SUBDIR);
        return Files.isDirectory(agentsDir);
    }

    @Override
    public ModuleLoadResult load(Path pluginDir, PluginSpec spec) {
        Path agentsDir = pluginDir.resolve(AGENTS_SUBDIR);
        log.info("Scanning agents from plugin '{}' (dir={})", spec.getName(), agentsDir);

        List<String> registered = new ArrayList<>();
        List<Path> registeredFiles = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(agentsDir)) {
            walk.filter(p -> {
                String fileName = p.getFileName().toString();
                return AGENT_YAML.equals(fileName) || AGENT_MD.equals(fileName);
            })
                    .forEach(agentFile -> {
                        // agent 目录名作为 agent 标识
                        Path parent = agentFile.getParent();
                        if (parent == null) {
                            return;
                        }
                        String agentId = parent.getFileName().toString();

                        if (!spec.getProvides().allowsAgent(agentId)) {
                            log.debug("Agent '{}' from plugin '{}' filtered by provides whitelist",
                                    agentId, spec.getName());
                            return;
                        }

                        try {
                            // 真实注册到 AgentRegistry（解析 agent.yaml 并写入 specCache）
                            agentRegistry.registerAgentSpec(agentFile.toAbsolutePath()).block();
                            registered.add(agentId);
                            registeredFiles.add(agentFile.toAbsolutePath());
                            log.debug("Registered agent '{}' from plugin '{}' (file={})",
                                    agentId, spec.getName(), agentFile);
                        } catch (Exception e) {
                            log.error("Plugin '{}' failed to register agent '{}': {}",
                                    spec.getName(), agentId, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to scan agents directory for plugin '{}'", spec.getName(), e);
            return ModuleLoadResult.failed(e);
        }

        // 记录已注册的文件路径，供 unload 使用
        registeredAgentFilesByPlugin.put(spec.getName(), List.copyOf(registeredFiles));

        log.info("Plugin '{}' registered {} agent(s): {}",
                spec.getName(), registered.size(), registered);
        return ModuleLoadResult.success(registered);
    }

    @Override
    public void unload(PluginSpec spec, ModuleLoadResult previousResult) {
        List<Path> files = registeredAgentFilesByPlugin.remove(spec.getName());
        if (files == null || files.isEmpty()) {
            if (previousResult != null && !previousResult.getLoadedItems().isEmpty()) {
                log.info("No registered agent files tracked for plugin '{}', skipping unload",
                        spec.getName());
            }
            return;
        }

        int removed = 0;
        for (Path agentFile : files) {
            try {
                if (agentRegistry.unregisterAgentSpec(agentFile)) {
                    removed++;
                }
            } catch (Exception e) {
                log.warn("Plugin '{}' failed to unregister agent '{}': {}",
                        spec.getName(), agentFile, e.getMessage());
            }
        }
        log.info("Plugin '{}' unloaded {}/{} agent(s)",
                spec.getName(), removed, files.size());
    }

    /**
     * 查询某个插件已注册的 agent.yaml 文件清单。
     *
     * @param pluginName 插件名
     * @return 文件路径的只读列表，永不为 {@code null}
     */
    public List<Path> getRegisteredAgentFiles(String pluginName) {
        List<Path> files = registeredAgentFilesByPlugin.get(pluginName);
        return files == null ? Collections.emptyList() : files;
    }
}
