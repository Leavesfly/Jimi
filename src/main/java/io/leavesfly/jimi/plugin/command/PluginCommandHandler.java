package io.leavesfly.jimi.plugin.command;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.plugin.PluginLoader;
import io.leavesfly.jimi.plugin.PluginRegistry;
import io.leavesfly.jimi.plugin.PluginRegistry.PluginState;
import io.leavesfly.jimi.plugin.dispatcher.ModuleLoadResult;
import io.leavesfly.jimi.plugin.dispatcher.PluginLoadResult;
import io.leavesfly.jimi.plugin.installer.PluginInstallResult;
import io.leavesfly.jimi.plugin.installer.PluginInstaller;
import io.leavesfly.jimi.plugin.spec.PluginScope;
import io.leavesfly.jimi.plugin.spec.PluginSpec;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 插件管理命令处理器
 *
 * <p>CLI 入口：{@code /plugin}
 * <ul>
 *   <li>{@code /plugin} / {@code /plugin list}：列出所有已注册插件</li>
 *   <li>{@code /plugin info &lt;name&gt;}：查看插件详情（模块状态、白名单等）</li>
 *   <li>{@code /plugin enable &lt;name&gt;}：启用一个被禁用的插件</li>
 *   <li>{@code /plugin disable &lt;name&gt;}：禁用一个已启用的插件</li>
 *   <li>{@code /plugin reload}：重新加载所有插件</li>
 *   <li>{@code /plugin install &lt;source&gt;}：从 GitHub {@code owner/repo} /
 *       HTTP(S) ZIP URL / 本地目录或 .zip 文件安装插件，安装后自动 reload</li>
 *   <li>{@code /plugin uninstall &lt;name&gt;}：从 {@code ~/.jimi/plugins/} 删除插件，
 *       随后自动 reload</li>
 * </ul>
 *
 * <p>使用 {@link Lazy} 注入 {@link PluginRegistry} 与 {@link PluginInstaller}，
 * 避免启动期循环依赖。
 */
@Slf4j
@Component
public class PluginCommandHandler implements CommandHandler {

    @Lazy
    @Autowired
    private PluginRegistry pluginRegistry;

    @Lazy
    @Autowired
    private PluginInstaller pluginInstaller;

    @Lazy
    @Autowired
    private PluginLoader pluginLoader;

    @Override
    public String getName() {
        return "plugin";
    }

    @Override
    public String getDescription() {
        return "管理 Jimi 插件（列表 / 启停 / 重载 / 安装 / 卸载）";
    }

    @Override
    public List<String> getAliases() {
        return List.of("plugins");
    }

    @Override
    public String getUsage() {
        return "/plugin [list | info <name> | enable <name> | disable <name> | reload"
                + " | install <source> | uninstall <name> | doctor [<name>]]";
    }

    @Override
    public String getCategory() {
        return "plugin";
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public void execute(CommandContext context) throws Exception {
        OutputFormatter out = context.getOutputFormatter();

        if (context.getArgCount() == 0) {
            listAllPlugins(out);
            return;
        }

        String sub = context.getArg(0);
        switch (sub) {
            case "list" -> listAllPlugins(out);
            case "info" -> {
                if (context.getArgCount() < 2) {
                    out.printError("用法: /plugin info <plugin-name>");
                    return;
                }
                showPluginDetails(context.getArg(1), out);
            }
            case "enable" -> {
                if (context.getArgCount() < 2) {
                    out.printError("用法: /plugin enable <plugin-name>");
                    return;
                }
                enablePlugin(context.getArg(1), out);
            }
            case "disable" -> {
                if (context.getArgCount() < 2) {
                    out.printError("用法: /plugin disable <plugin-name>");
                    return;
                }
                disablePlugin(context.getArg(1), out);
            }
            case "reload" -> reloadAll(out);
            case "install" -> {
                if (context.getArgCount() < 2) {
                    out.printError("用法: /plugin install <owner/repo | https://...zip | /path/to/plugin>");
                    return;
                }
                installPlugin(context.getArg(1), out);
            }
            case "uninstall", "remove" -> {
                if (context.getArgCount() < 2) {
                    out.printError("用法: /plugin uninstall <plugin-name>");
                    return;
                }
                uninstallPlugin(context.getArg(1), out);
            }
            case "doctor" -> {
                String target = context.getArgCount() >= 2 ? context.getArg(1) : null;
                runDoctor(target, out);
            }
            default -> {
                // 作为 /plugin <name> 的便捷语法，等价于 info
                showPluginDetails(sub, out);
            }
        }
    }

    // ==================== 子命令实现 ====================

    /** 列出所有插件 */
    private void listAllPlugins(OutputFormatter out) {
        List<PluginState> all = pluginRegistry.list();
        Map<String, Object> stats = pluginRegistry.getStatistics();

        out.println();
        out.printSuccess(String.format("插件列表 (共 %d 个, 启用 %s, 禁用 %s, 拒绝 %s)",
                all.size(), stats.get("enabled"), stats.get("disabled"), stats.get("rejected")));
        out.println();

        if (all.isEmpty()) {
            out.println("  暂无已加载的插件");
            out.println();
            out.printInfo("提示: 在 ~/.jimi/plugins/ 或 <project>/.jimi/plugins/ 目录下");
            out.printInfo("      创建 plugin.yaml 来添加插件");
            out.println();
            return;
        }

        for (PluginState state : all) {
            PluginSpec spec = state.spec;
            String icon;
            String statusText;
            if (state.rejectReason != null) {
                icon = "⛔";
                statusText = "已拒绝: " + state.rejectReason;
            } else if (state.enabled) {
                icon = "✅";
                statusText = "已启用";
            } else {
                icon = "⏸️";
                statusText = "已禁用";
            }
            out.println(String.format("  %s %-24s v%-8s [%s] - %s",
                    icon, spec.getName(), spec.getVersion(),
                    spec.getScope(), spec.getDescription()));
            if (!state.enabled || state.rejectReason != null) {
                out.println("      └─ " + statusText);
            }
        }
        out.println();
        out.printInfo("使用 '/plugin info <name>' 查看插件详情");
        out.println();
    }

    /** 显示插件详情 */
    private void showPluginDetails(String name, OutputFormatter out) {
        PluginState state = pluginRegistry.findByName(name).orElse(null);
        if (state == null) {
            out.printError("未找到插件: " + name);
            out.printInfo("使用 '/plugin list' 查看所有已加载插件");
            return;
        }

        PluginSpec spec = state.spec;
        out.println();
        out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        out.printSuccess("插件详情: " + spec.getName());
        out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        out.println();

        out.println("📝 基本信息:");
        out.println("  名称:       " + spec.getName());
        out.println("  版本:       " + spec.getVersion());
        out.println("  描述:       " + spec.getDescription());
        out.println("  作用域:     " + spec.getScope());
        if (spec.getAuthor() != null) {
            out.println("  作者:       " + spec.getAuthor());
        }
        if (spec.getLicense() != null) {
            out.println("  许可:       " + spec.getLicense());
        }
        if (spec.getPluginDir() != null) {
            out.println("  安装路径:   " + spec.getPluginDir());
        }

        String status;
        if (state.rejectReason != null) {
            status = "⛔ 已拒绝（" + state.rejectReason + "）";
        } else if (state.enabled) {
            status = "✅ 已启用";
        } else {
            status = "⏸️  已禁用";
        }
        out.println("  状态:       " + status);
        out.println();

        // 兼容性
        if (spec.getCompatibility() != null) {
            out.println("🔧 兼容性:");
            if (spec.getCompatibility().getJimiVersion() != null) {
                out.println("  Jimi 版本:  " + spec.getCompatibility().getJimiVersion());
            }
            if (spec.getCompatibility().getJavaVersion() != null) {
                out.println("  Java 版本:  " + spec.getCompatibility().getJavaVersion());
            }
            if (spec.getCompatibility().getOs() != null && !spec.getCompatibility().getOs().isEmpty()) {
                out.println("  操作系统:   " + spec.getCompatibility().getOs());
            }
            out.println();
        }

        // 白名单 / 扩展点
        out.println("🧩 扩展点白名单:");
        printProvideLine(out, "Skills   ", spec.getProvides().getSkills());
        printProvideLine(out, "Hooks    ", spec.getProvides().getHooks());
        printProvideLine(out, "Commands ", spec.getProvides().getCommands());
        printProvideLine(out, "MCP      ", spec.getProvides().getMcpServers());
        printProvideLine(out, "Agents   ", spec.getProvides().getAgents());
        out.println();

        // 模块加载结果（MCP 配置文件通过 PluginRegistry 汇总查询得到，用于展示 discovery-only 详情）
        if (state.loadResult != null) {
            List<Path> mcpConfigFiles = pluginRegistry.getDiscoveredMcpConfigFiles(spec.getName());
            printLoadResult(state.loadResult, out, mcpConfigFiles);
        }

        // 依赖
        if (spec.getDependencies() != null && !spec.getDependencies().isEmpty()) {
            out.println("🔗 插件依赖:");
            spec.getDependencies().forEach(dep ->
                    out.println("  - " + dep.getName() + " " + dep.getVersion()));
            out.println();
        }
    }

    /** 打印单个扩展点的白名单行 */
    private void printProvideLine(OutputFormatter out, String label, List<String> items) {
        if (items == null || items.isEmpty()) {
            out.println("  " + label + ": (未使用)");
        } else {
            out.println("  " + label + ": " + String.join(", ", items));
        }
    }

    /**
     * 打印插件加载结果。
     *
     * <p><b>Discovery-only 模块</b>（{@code mcp} / {@code agents}）的展示规则：
     * <ul>
     *   <li>图标改用 🔍 以区别于真正注入 Registry 的模块（✅）</li>
     *   <li>额外追加 "(discovery-only)" 标注，提示用户这些扩展项仅被发现而未自动生效</li>
     *   <li>{@code mcp} 模块追加实际发现的配置文件路径，方便用户把它们传给
     *       {@code --mcp-config-file} 或在 {@code JimiFactory} 中汇总使用</li>
     * </ul>
     *
     * @param result             插件加载结果
     * @param out                输出通道
     * @param mcpConfigFiles     该插件发现的 MCP 配置文件（可为空列表，不允许 {@code null}）
     */
    private void printLoadResult(PluginLoadResult result, OutputFormatter out, List<Path> mcpConfigFiles) {
        out.println("📦 加载结果:");
        Map<String, ModuleLoadResult> moduleResults = result.getModuleResults();
        if (moduleResults.isEmpty()) {
            out.println("  (无任何模块被加载)");
            out.println();
            return;
        }
        for (Map.Entry<String, ModuleLoadResult> e : moduleResults.entrySet()) {
            ModuleLoadResult m = e.getValue();
            String moduleName = e.getKey();
            boolean sessionScoped = isSessionScopedModule(moduleName);

            String icon;
            if (!m.isSuccess()) {
                icon = "❌";
            } else if (sessionScoped) {
                icon = "ℹ️";
            } else {
                icon = "✅";
            }
            String items = (m.getLoadedItems() == null ? 0 : m.getLoadedItems().size()) + " 个";
            String suffix = sessionScoped ? " (会话级, reload 需重启)" : "";
            String errSuffix = m.getErrorMessage() != null && !m.getErrorMessage().isEmpty()
                    ? " (" + m.getErrorMessage() + ")"
                    : "";
            out.println(String.format("  %s %-10s - %s%s%s",
                    icon, moduleName, items, suffix, errSuffix));

            // mcp 模块额外展示实际发现的配置文件路径
            if ("mcp".equals(moduleName) && mcpConfigFiles != null && !mcpConfigFiles.isEmpty()) {
                for (Path configFile : mcpConfigFiles) {
                    out.println("      └─ 配置: " + configFile);
                }
            }
        }
        out.println();
    }

    /**
     * 判断某个模块是否为 session-scoped 模块（会话级生效，运行时 reload 需重启会话）。
     *
     * <p>当前：{@code mcp} 是 session-scoped——会话创建时由 {@code JimiFactory}
     * 自动合并插件 MCP 配置到 {@code MCPToolProvider}，但运行时 reload
     * 无法动态增删已创建会话的 MCP 工具，需重启会话生效。
     *
     * <p>{@code agents} 已改为运行时动态注册（{@code AgentModuleAdapter}
     * 真实注入 {@code AgentRegistry}），不再需要特殊标注。
     * {@code skills} / {@code hooks} / {@code commands} 同样即时生效。
     */
    private boolean isSessionScopedModule(String moduleName) {
        return "mcp".equals(moduleName);
    }

    /** 启用插件 */
    private void enablePlugin(String name, OutputFormatter out) {
        if (!pluginRegistry.has(name)) {
            out.printError("未找到插件: " + name);
            return;
        }
        boolean ok = pluginRegistry.enable(name);
        if (ok) {
            out.printSuccess("已启用插件: " + name);
        } else {
            out.printInfo("插件已处于启用状态: " + name);
        }
    }

    /** 禁用插件 */
    private void disablePlugin(String name, OutputFormatter out) {
        if (!pluginRegistry.has(name)) {
            out.printError("未找到插件: " + name);
            return;
        }
        boolean ok = pluginRegistry.disable(name);
        if (ok) {
            out.printSuccess("已禁用插件: " + name);
        } else {
            out.printInfo("插件已处于禁用状态: " + name);
        }
    }

    /** 重新加载所有插件 */
    private void reloadAll(OutputFormatter out) {
        out.printInfo("正在重新加载所有插件...");
        pluginRegistry.reload();
        Map<String, Object> stats = pluginRegistry.getStatistics();
        out.printSuccess(String.format("插件已重新加载 (共 %s 个, 启用 %s)",
                stats.get("totalPlugins"), stats.get("enabled")));
    }

    /**
     * 安装插件。
     *
     * <p>委托给 {@link PluginInstaller#install(String)}，安装成功后自动触发
     * {@link PluginRegistry#reload()} 让新插件立即生效。任何异常都被捕获并
     * 转为 {@link OutputFormatter#printError} 输出，不让命令执行链路抛出。
     *
     * @param source 来源字符串（GitHub {@code owner/repo} / HTTPS ZIP URL / 本地路径）
     * @param out    输出通道
     */
    private void installPlugin(String source, OutputFormatter out) {
        out.printInfo("正在安装插件: " + source);
        try {
            PluginInstallResult result = pluginInstaller.install(source);
            PluginSpec installedSpec = result.getSpec();
            out.printSuccess(String.format("插件已安装: %s v%s",
                    installedSpec.getName(), installedSpec.getVersion()));
            out.printInfo("安装位置: " + result.getInstalledDir());

            // 自动 reload 让新插件在当前会话立即生效
            pluginRegistry.reload();
            Map<String, Object> stats = pluginRegistry.getStatistics();
            out.printSuccess(String.format("插件已激活 (当前共 %s 个, 启用 %s)",
                    stats.get("totalPlugins"), stats.get("enabled")));
        } catch (Exception e) {
            log.error("Failed to install plugin from: {}", source, e);
            out.printError("安装失败: " + e.getMessage());
        }
    }

    /**
     * 卸载插件。
     *
     * <p>委托给 {@link PluginInstaller#uninstall(String)} 删除
     * {@code ~/.jimi/plugins/&lt;name&gt;} 目录，随后触发 reload 刷新注册表。
     *
     * @param name 插件名
     * @param out  输出通道
     */
    private void uninstallPlugin(String name, OutputFormatter out) {
        try {
            boolean removed = pluginInstaller.uninstall(name);
            if (!removed) {
                out.printError("未找到已安装的插件: " + name);
                out.printInfo("提示: 仅能卸载 ~/.jimi/plugins/ 下的用户级插件");
                return;
            }
            out.printSuccess("已卸载插件: " + name);
            pluginRegistry.reload();
            Map<String, Object> stats = pluginRegistry.getStatistics();
            out.printInfo(String.format("当前共 %s 个插件 (启用 %s)",
                    stats.get("totalPlugins"), stats.get("enabled")));
        } catch (Exception e) {
            log.error("Failed to uninstall plugin: {}", name, e);
            out.printError("卸载失败: " + e.getMessage());
        }
    }

    /**
     * 诊断插件的 {@code requirements} 与 {@code compatibility} 是否满足。
     *
     * <p>复用 {@link PluginLoader#checkCompatibility} 与
     * {@link PluginLoader#checkRequirements}，不造新轮子。
     *
     * @param targetName 指定插件名；为 {@code null} 时诊断所有已加载插件
     * @param out        输出通道
     */
    private void runDoctor(String targetName, OutputFormatter out) {
        String jimiVersion = pluginLoader.getCurrentJimiVersion();

        List<PluginState> targets;
        if (targetName != null) {
            PluginState one = pluginRegistry.findByName(targetName).orElse(null);
            if (one == null) {
                out.printError("未找到插件: " + targetName);
                return;
            }
            targets = List.of(one);
        } else {
            targets = pluginRegistry.list();
            if (targets.isEmpty()) {
                out.printInfo("当前没有已加载的插件");
                return;
            }
        }

        out.println();
        out.printSuccess("插件诊断报告 (Jimi v" + jimiVersion + ")");
        out.println();

        int passed = 0;
        int failed = 0;
        for (PluginState state : targets) {
            PluginSpec spec = state.spec;
            out.println("🔍 " + spec.getName() + " v" + spec.getVersion()
                    + " [" + spec.getScope() + "]");

            PluginLoader.CheckResult compat = pluginLoader.checkCompatibility(spec, jimiVersion);
            printDoctorLine(out, "compatibility", compat);

            PluginLoader.CheckResult reqs = pluginLoader.checkRequirements(spec);
            printDoctorLine(out, "requirements ", reqs);

            if (compat.passed() && reqs.passed()) {
                passed++;
                out.println("  ✅ OK");
            } else {
                failed++;
                out.println("  ⛔ 需要关注");
            }
            out.println();
        }

        out.printSuccess(String.format("诊断完成: 通过 %d 个, 异常 %d 个", passed, failed));
        out.println();
    }

    /** 诊断单行输出 */
    private void printDoctorLine(OutputFormatter out, String label,
                                 PluginLoader.CheckResult result) {
        if (result.passed()) {
            out.println("  ✅ " + label + " : OK");
        } else {
            out.println("  ❌ " + label + " : " + result.reason());
        }
    }

    /** 仅供测试：判断当前 scope 是否展示（保留扩展位） */
    @SuppressWarnings("unused")
    private boolean isScopeVisible(PluginScope scope) {
        return scope != null;
    }
}
