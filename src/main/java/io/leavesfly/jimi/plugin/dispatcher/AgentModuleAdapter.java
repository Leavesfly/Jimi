package io.leavesfly.jimi.plugin.dispatcher;

import io.leavesfly.jimi.plugin.spec.PluginSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Agents 扩展点适配器
 *
 * <p>扫描 {@code <plugin>/agents/} 目录下的所有 {@code agent.yaml} 文件（递归），
 * 并登记到 {@link ModuleLoadResult} 中，供 {@code JimiFactory} 在创建 Engine
 * 时消费（把插件级 Agent 目录作为子 Agent 搜索路径）。
 *
 * <p><b>MVP 限制</b>：当前版本不直接往 {@code AgentRegistry} 注入 Agent。这是因为
 * {@code AgentSpecLoader} 采用 {@code @PostConstruct} 写死扫描路径的模式，动态追加
 * 需要改动核心类。本 Adapter 仅做"发现与登记"，让用户通过 {@code /plugin info}
 * 能看到插件声明的 Agent。真实注册由 {@code PluginRegistry} 通过查询接口
 * {@code getAgentDirs()} 暴露给下游消费者。
 *
 * <p><b>白名单行为</b>：按 {@link PluginSpec#getProvides()} 的 {@code agents}
 * 过滤，若为空则放行所有发现项。
 *
 * <p><b>可逆性</b>：{@link #unload} 仅清理登记表，因为没有真正注册到全局
 * 注册中心。
 */
@Slf4j
@Component
public class AgentModuleAdapter implements PluginModuleAdapter {

    /** 模块名（对应 plugin.yaml 里的 defaults.modules.agents） */
    public static final String MODULE_NAME = "agents";

    /** Agent 子目录名 */
    private static final String AGENTS_SUBDIR = "agents";

    /** Agent 定义文件名 */
    private static final String AGENT_YAML = "agent.yaml";

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

        List<String> discovered = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(agentsDir)) {
            walk.filter(p -> AGENT_YAML.equals(p.getFileName().toString()))
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
                        discovered.add(agentId);
                        log.debug("Discovered agent: {} (from {})", agentId, agentFile);
                    });
        } catch (IOException e) {
            log.error("Failed to scan agents directory for plugin '{}'", spec.getName(), e);
            return ModuleLoadResult.failed(e);
        }

        log.info("Plugin '{}' contributes {} agent(s): {}",
                spec.getName(), discovered.size(), discovered);
        return ModuleLoadResult.success(discovered);
    }

    @Override
    public void unload(PluginSpec spec, ModuleLoadResult previousResult) {
        // MVP 阶段：未真正注册，无需实际卸载
        if (previousResult != null && !previousResult.getLoadedItems().isEmpty()) {
            log.info("Unloading {} discovered agent(s) from plugin '{}' (no-op in MVP)",
                    previousResult.getLoadedItems().size(), spec.getName());
        }
    }
}
