package io.leavesfly.jimi.plugin.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.mcp.MCPConfig;
import io.leavesfly.jimi.plugin.spec.PluginSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 扩展点适配器
 *
 * <p>扫描 {@code <plugin>/mcp/} 目录下的 {@code servers.json}（或任意 {@code *.json}
 * MCP 配置文件），解析为 {@link MCPConfig}，按 {@link PluginSpec#getProvides()} 的
 * {@code mcp_servers} 白名单过滤，将通过过滤的 server 名登记到 {@link ModuleLoadResult}。
 *
 * <p><b>MVP 限制</b>：{@code MCPToolProvider} 的 {@code mcpConfigFiles} 在 Engine
 * 创建时由 {@code JimiFactory} 一次性传入，运行时不支持增删。因此本 Adapter 的 load
 * 只做"发现与登记"，{@code JimiFactory} 需在创建 Engine 时查询
 * {@link io.leavesfly.jimi.plugin.PluginRegistry} 获取所有插件提供的 MCP 配置文件，
 * 合并后一次性传入 {@code mcpConfigFiles}。
 *
 * <p><b>白名单行为</b>：按 {@link PluginSpec#getProvides()} 的 {@code mcpServers}
 * 过滤，若为空则放行所有 server。过滤后的 server 名在 {@code getLoadedItems()} 中返回。
 *
 * <p><b>可逆性</b>：{@link #unload} 仅清理登记表，无实际回滚。
 */
@Slf4j
@Component
public class McpModuleAdapter implements PluginModuleAdapter {

    /** 模块名（对应 plugin.yaml 里的 defaults.modules.mcp） */
    public static final String MODULE_NAME = "mcp";

    /** MCP 子目录名 */
    private static final String MCP_SUBDIR = "mcp";

    /** 默认 MCP 配置文件名（单文件优先） */
    private static final String DEFAULT_CONFIG_FILE = "servers.json";

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 每个插件发现的 MCP 配置文件清单。
     *
     * <p>Key：插件名；Value：对应插件 {@code mcp/} 目录下实际被加载的 {@code *.json} 文件。
     *
     * <p>用途：{@code JimiFactory} 在创建 Engine 时需要把所有插件提供的 MCP 配置文件
     * 汇总并一次性传给 {@code MCPToolProvider}；{@code /plugin info} 也用它展示
     * "discovery-only" 的实际文件路径。
     *
     * <p>使用 {@link ConcurrentHashMap} 保证并发安全——reload 时可能并发 load/unload。
     */
    private final Map<String, List<Path>> discoveredConfigFilesByPlugin = new ConcurrentHashMap<>();

    @Override
    public String getModuleName() {
        return MODULE_NAME;
    }

    @Override
    public boolean supports(Path pluginDir) {
        if (pluginDir == null) {
            return false;
        }
        Path mcpDir = pluginDir.resolve(MCP_SUBDIR);
        return Files.isDirectory(mcpDir);
    }

    @Override
    public ModuleLoadResult load(Path pluginDir, PluginSpec spec) {
        Path mcpDir = pluginDir.resolve(MCP_SUBDIR);
        log.info("Scanning MCP configs from plugin '{}' (dir={})", spec.getName(), mcpDir);

        // 优先读单文件 servers.json；若不存在则遍历所有 *.json
        List<Path> configFiles = collectConfigFiles(mcpDir);
        if (configFiles.isEmpty()) {
            log.debug("No MCP config files found in plugin '{}'", spec.getName());
            // 清空之前该插件的遗留记录，保证 reload 语义
            discoveredConfigFilesByPlugin.remove(spec.getName());
            return ModuleLoadResult.success(List.of());
        }

        // 登记该插件发现的所有配置文件（供 JimiFactory / /plugin info 消费）
        discoveredConfigFilesByPlugin.put(spec.getName(), List.copyOf(configFiles));

        List<String> allowedServers = new ArrayList<>();
        for (Path configFile : configFiles) {
            try {
                MCPConfig config = objectMapper.readValue(configFile.toFile(), MCPConfig.class);
                if (config.getMcpServers() == null) {
                    continue;
                }
                for (Map.Entry<String, MCPConfig.ServerConfig> entry : config.getMcpServers().entrySet()) {
                    String serverName = entry.getKey();
                    if (!spec.getProvides().allowsMcpServer(serverName)) {
                        log.debug("MCP server '{}' from plugin '{}' filtered by provides whitelist",
                                serverName, spec.getName());
                        continue;
                    }
                    allowedServers.add(serverName);
                    log.debug("Discovered MCP server: {} (from {})", serverName, configFile);
                }
            } catch (IOException e) {
                log.warn("Failed to parse MCP config '{}' for plugin '{}': {}",
                        configFile, spec.getName(), e.getMessage());
                // 单个配置文件解析失败不影响其他
            }
        }

        log.info("Plugin '{}' contributes {} MCP server(s): {}",
                spec.getName(), allowedServers.size(), allowedServers);
        return ModuleLoadResult.success(allowedServers);
    }

    @Override
    public void unload(PluginSpec spec, ModuleLoadResult previousResult) {
        // MVP 阶段：未真正接入 MCPToolProvider 的运行时增删，仅清理发现记录
        List<Path> removed = discoveredConfigFilesByPlugin.remove(spec.getName());
        if (previousResult != null && !previousResult.getLoadedItems().isEmpty()) {
            log.info("Unloading {} discovered MCP server(s) from plugin '{}' (cleared {} config file(s))",
                    previousResult.getLoadedItems().size(), spec.getName(),
                    removed == null ? 0 : removed.size());
        }
    }

    /**
     * 查询某个插件发现的 MCP 配置文件清单。
     *
     * <p>仅在插件的 {@link #load} 成功且至少发现一个 {@code *.json} 文件时有值；
     * 其他情况（插件未注册 / 无 mcp 目录 / 已 unload）返回空列表。
     *
     * @param pluginName 插件名
     * @return 配置文件路径的不可变列表，永不为 {@code null}
     */
    public List<Path> getDiscoveredConfigFiles(String pluginName) {
        List<Path> files = discoveredConfigFilesByPlugin.get(pluginName);
        return files == null ? Collections.emptyList() : files;
    }

    /**
     * 汇总所有插件发现的 MCP 配置文件（按插件维度）。
     *
     * <p>返回的 Map 为只读快照；{@code JimiFactory} 创建 Engine 时可直接
     * 把所有 value 展平后传给 {@code MCPToolProvider.setMcpConfigFiles}。
     *
     * @return {@code pluginName -> configFiles} 的只读快照
     */
    public Map<String, List<Path>> getAllDiscoveredConfigFiles() {
        return Collections.unmodifiableMap(new java.util.LinkedHashMap<>(discoveredConfigFilesByPlugin));
    }

    /**
     * 收集插件 MCP 目录下的所有配置文件。
     *
     * <p>优先策略：若 {@code servers.json} 存在，只读该文件；否则枚举所有
     * {@code *.json} 文件。
     */
    private List<Path> collectConfigFiles(Path mcpDir) {
        List<Path> result = new ArrayList<>();
        Path defaultFile = mcpDir.resolve(DEFAULT_CONFIG_FILE);
        if (Files.isRegularFile(defaultFile)) {
            result.add(defaultFile);
            return result;
        }
        try (var stream = Files.newDirectoryStream(mcpDir, "*.json")) {
            stream.forEach(result::add);
        } catch (IOException e) {
            log.warn("Failed to list JSON files under {}: {}", mcpDir, e.getMessage());
        }
        return result;
    }
}
