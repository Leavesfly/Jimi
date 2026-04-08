package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.core.agent.AgentRegistry;
import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

/**
 * /agents 命令处理器
 * 管理和查看系统中的代理
 * <p>
 * 支持的操作：
 * - /agents: 列出所有可用的代理
 * - /agents <agent-name>: 查看指定代理的详细信息
 * - /agents run <agent-name>: 切换到指定代理
 */
@Slf4j
@Component
public class AgentsCommandHandler implements CommandHandler {

    @Autowired
    private AgentRegistry agentRegistry;

    @Override
    public String getName() {
        return "agents";
    }

    @Override
    public String getDescription() {
        return "管理和查看系统中的代理";
    }

    @Override
    public String getUsage() {
        return "/agents [agent-name | run <agent-name>]";
    }

    @Override
    public void execute(CommandContext context) throws Exception {
        // 根据参数数量分发到不同的处理方法
        if (context.getArgCount() == 0) {
            listAllAgents(context);
        } else if (context.getArgCount() == 1) {
            showAgentDetails(context, normalizeAgentName(context.getArg(0)));
        } else if (context.getArgCount() == 2 && "run".equals(context.getArg(0))) {
            runAgent(context, normalizeAgentName(context.getArg(1)));
        } else {
            showUsageHelp(context);
        }
    }

    /**
     * 规范化代理名称，去除尖括号等格式字符
     *
     * @param agentName 原始代理名称，可能包含 <> 等字符
     * @return 规范化后的代理名称
     */
    private String normalizeAgentName(String agentName) {
        if (agentName == null) {
            return null;
        }

        // 去除尖括号
        String normalized = agentName.trim();
        if (normalized.startsWith("<") && normalized.endsWith(">")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }

        return normalized.trim();
    }

    /**
     * 列出所有可用的 Agent
     */
    private void listAllAgents(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();

        // 获取所有 Agent 规范
        Map<Path, AgentSpec> specCache = agentRegistry.getAllAgentSpecs();

        out.println();
        out.printSuccess("可用代理列表:");
        out.println();

        if (specCache.isEmpty()) {
            out.printWarning("未找到任何代理配置");
            out.println();
            return;
        }

        // 分类存储
        List<AgentSpec> generalAgents = new ArrayList<>();
        List<AgentSpec> specializedAgents = new ArrayList<>();

        // 分类逻辑（根据 name 判断）
        for (AgentSpec spec : specCache.values()) {
            if ("Default Agent".equals(spec.getName())) {
                generalAgents.add(spec);
            } else {
                specializedAgents.add(spec);
            }
        }

        // 输出通用代理
        if (!generalAgents.isEmpty()) {
            out.printInfo("📦 通用代理:");
            generalAgents.stream()
                    .sorted(Comparator.comparing(AgentSpec::getName))
                    .forEach(spec -> out.println("  • " + spec.getName()));
            out.println();
        }

        // 输出专业代理
        if (!specializedAgents.isEmpty()) {
            out.printInfo("🔧 专业代理:");
            specializedAgents.stream()
                    .sorted(Comparator.comparing(AgentSpec::getName))
                    .forEach(spec -> out.println("  • " + spec.getName()));
            out.println();
        }

        out.println("总计: " + specCache.size() + " 个代理");
        out.println();
        out.printInfo("提示:");
        out.println("  • 查看详情: /agents <agent-name>");
        out.println("  • 切换代理: /agents run <agent-name>");
        out.println();
    }

    /**
     * 查看指定 Agent 的详细信息
     */
    private void showAgentDetails(CommandContext context, String agentName) {
        OutputFormatter out = context.getOutputFormatter();

        // 查找 Agent
        Map<Path, AgentSpec> specCache = agentRegistry.getAllAgentSpecs();
        AgentSpec targetSpec = specCache.values().stream()
                .filter(spec -> agentName.equals(spec.getName()))
                .findFirst()
                .orElse(null);

        if (targetSpec == null) {
            out.println();
            out.printError("未找到代理: " + agentName);
            out.println();
            List<String> availableAgents = agentRegistry.listAvailableAgents();
            if (!availableAgents.isEmpty()) {
                out.printInfo("可用代理列表: " + String.join(", ", availableAgents));
                out.println();
            }
            return;
        }

        out.println();
        out.printSuccess("代理详细信息: " + agentName);
        out.println();

        // 基本信息
        out.printInfo("📝 基本信息:");
        out.println("  名称: " + targetSpec.getName());
        out.println();

        // 工具列表
        out.printInfo("🛠️ 工具列表:");
        if (targetSpec.getTools() != null && !targetSpec.getTools().isEmpty()) {
            int toolCount = targetSpec.getTools().size();
            int displayLimit = 10;

            targetSpec.getTools().stream()
                    .limit(displayLimit)
                    .forEach(tool -> out.println("  • " + tool));

            if (toolCount > displayLimit) {
                out.println("  ... (共 " + toolCount + " 个工具)");
            } else {
                out.println("  (共 " + toolCount + " 个工具)");
            }
        } else {
            out.println("  (无)");
        }
        out.println();

        // 排除工具
        if (targetSpec.getExcludeTools() != null && !targetSpec.getExcludeTools().isEmpty()) {
            out.printInfo("🚫 排除工具:");
            targetSpec.getExcludeTools().forEach(tool -> out.println("  • " + tool));
            out.println();
        }

        // 子代理
        if (targetSpec.getSubagents() != null && !targetSpec.getSubagents().isEmpty()) {
            out.printInfo("🤖 子代理:");
            targetSpec.getSubagents().forEach((name, subagent) -> {
                String description = subagent.getDescription() != null ?
                        subagent.getDescription() : "(无描述)";
                out.println("  • " + name + " - " + description);
            });
            out.println();
        }

        // 系统提示词信息
        out.printInfo("💬 系统提示词:");
        out.println("  文件: " + targetSpec.getSystemPromptPath());
        if (targetSpec.getSystemPromptArgs() != null && !targetSpec.getSystemPromptArgs().isEmpty()) {
            out.println("  参数:");
            targetSpec.getSystemPromptArgs().forEach((key, value) ->
                    out.println("    - " + key + ": " + value)
            );
        }
        out.println();

        out.printInfo("提示: 使用 /agents run " + agentName + " 切换到此代理");
        out.println();
    }

    /**
     * 切换到指定 Agent
     * <p>
     * 当前版本暂不支持运行时切换代理，仅验证代理是否存在并提示用户重启。
     */
    private void runAgent(CommandContext context, String agentName) {
        OutputFormatter out = context.getOutputFormatter();

        // 检查当前是否已经是该 Agent
        if (agentName.equals(context.getEngineClient().getAgentName())) {
            out.println();
            out.printWarning("已经在使用代理: " + agentName);
            out.println();
            return;
        }

        // 查找 Agent 配置路径，验证代理是否存在
        Path agentPath = findAgentPath(agentName);
        if (agentPath == null) {
            out.println();
            out.printError("未找到代理: " + agentName);
            List<String> availableAgents = agentRegistry.listAvailableAgents();
            if (!availableAgents.isEmpty()) {
                out.printInfo("可用代理: " + String.join(", ", availableAgents));
            }
            out.println();
            return;
        }

        // 当前版本暂不支持运行时切换代理
        out.println();
        out.printWarning("⚠️  当前版本暂不支持运行时切换代理");
        out.printInfo("请使用以下方式切换代理:");
        out.println("  1. 退出当前会话");
        out.println("  2. 使用 --agent 参数重新启动:");
        out.println("     jimi --agent " + agentName);
        out.println();
    }

    /**
     * 查找 Agent 配置文件路径
     */
    private Path findAgentPath(String agentName) {
        Map<Path, AgentSpec> specCache = agentRegistry.getAllAgentSpecs();
        return specCache.entrySet().stream()
                .filter(entry -> agentName.equals(entry.getValue().getName()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /**
     * 显示使用帮助
     */
    private void showUsageHelp(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();

        out.println();
        out.printInfo("用法:");
        out.println("  /agents                    - 列出所有可用的代理");
        out.println("  /agents <agent-name>       - 查看指定代理的详细信息");
        out.println("  /agents run <agent-name>   - 切换到指定代理");
        out.println();
    }
}
