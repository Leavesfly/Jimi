package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.command.custom.CustomCommandRegistry;
import io.leavesfly.jimi.command.custom.CustomCommandSpec;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 自定义命令管理处理器
 *
 * 提供自定义命令的管理功能:
 * - /commands: 列出所有自定义命令
 * - /commands list: 列出所有自定义命令
 * - /commands [name]: 查看指定命令的详细信息
 * - /commands reload: 重新加载所有自定义命令
 * - /commands enable [name]: 启用命令
 * - /commands disable [name]: 禁用命令
 */
@Slf4j
@Component
public class CommandsCommandHandler implements CommandHandler {

    @Lazy
    @Autowired
    private CustomCommandRegistry customCommandRegistry;

    @Override
    public String getName() {
        return "commands";
    }

    @Override
    public String getDescription() {
        return "管理自定义命令";
    }

    @Override
    public List<String> getAliases() {
        return List.of("cmds");
    }

    @Override
    public String getUsage() {
        return "/commands [list|<name>|reload|enable <name>|disable <name>]";
    }

    @Override
    public String getCategory() {
        return "system";
    }

    @Override
    public void execute(CommandContext context) throws Exception {
        OutputFormatter out = context.getOutputFormatter();

        if (context.getArgCount() == 0) {
            listAllCommands(out);
            return;
        }

        String subCommand = context.getArg(0);

        switch (subCommand) {
            case "list" -> listAllCommands(out);
            case "reload" -> reloadCommands(context, out);
            case "enable" -> {
                if (context.getArgCount() < 2) {
                    out.printError("用法: /commands enable <command-name>");
                    return;
                }
                enableCommand(context.getArg(1), out);
            }
            case "disable" -> {
                if (context.getArgCount() < 2) {
                    out.printError("用法: /commands disable <command-name>");
                    return;
                }
                disableCommand(context.getArg(1), out);
            }
            default -> showCommandDetails(subCommand, out);
        }
    }

    /**
     * 列出所有自定义命令
     */
    private void listAllCommands(OutputFormatter out) {
        List<CustomCommandSpec> commands = customCommandRegistry.getAllCustomCommands();

        out.println();
        out.printSuccess("自定义命令列表 (" + commands.size() + " 个):");
        out.println();

        if (commands.isEmpty()) {
            out.println("  暂无自定义命令");
            out.println();
            out.printInfo("提示: 在 ~/.jimi/commands/ 或 <project>/.jimi/commands/ 目录下");
            out.printInfo("      创建 YAML 配置文件来添加自定义命令");
            out.println();
            return;
        }

        commands.stream()
                .collect(Collectors.groupingBy(CustomCommandSpec::getCategory))
                .forEach((category, categoryCommands) -> {
                    out.println("📦 " + category.toUpperCase());
                    categoryCommands.forEach(cmd -> {
                        String status = cmd.isEnabled() ? "✅" : "❌";
                        String aliases = cmd.getAliases().isEmpty() ? ""
                                : " [" + String.join(", ", cmd.getAliases()) + "]";
                        out.println(String.format("  %s %-20s - %s%s",
                                status, cmd.getName(), cmd.getDescription(), aliases));
                    });
                    out.println();
                });

        out.printInfo("使用 '/commands <name>' 查看命令详情");
        out.println();
    }

    /**
     * 显示命令详情
     */
    private void showCommandDetails(String commandName, OutputFormatter out) {
        CustomCommandSpec spec = customCommandRegistry.getCommandSpec(commandName);

        if (spec == null) {
            out.printError("未找到自定义命令: " + commandName);
            out.printInfo("使用 '/commands' 查看所有自定义命令");
            return;
        }

        out.println();
        out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        out.printSuccess("命令详情: " + spec.getName());
        out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        out.println();

        out.println("📝 基本信息:");
        out.println("  名称:     " + spec.getName());
        out.println("  描述:     " + spec.getDescription());
        out.println("  分类:     " + spec.getCategory());
        out.println("  状态:     " + (spec.isEnabled() ? "✅ 启用" : "❌ 禁用"));
        out.println("  优先级:   " + spec.getPriority());
        out.println("  用法:     " + (spec.getUsage() != null ? spec.getUsage() : "/" + spec.getName()));

        if (!spec.getAliases().isEmpty()) {
            out.println("  别名:     " + String.join(", ", spec.getAliases()));
        }
        out.println();

        out.println("⚙️  执行配置:");
        out.println("  类型:     " + spec.getExecutionTypeName());

        if (spec.isPromptType()) {
            String promptPreview = spec.getPrompt().length() > 80
                    ? spec.getPrompt().substring(0, 77) + "..."
                    : spec.getPrompt();
            out.println("  Prompt:   " + promptPreview);
        } else if (spec.getExecution() != null) {
            switch (spec.getExecution().getType()) {
                case "script" -> {
                    if (spec.getExecution().getScriptFile() != null) {
                        out.println("  脚本文件: " + spec.getExecution().getScriptFile());
                    } else if (spec.getExecution().getScript() != null) {
                        String scriptPreview = spec.getExecution().getScript().length() > 50
                                ? spec.getExecution().getScript().substring(0, 47) + "..."
                                : spec.getExecution().getScript();
                        out.println("  脚本:     " + scriptPreview);
                    }
                }
                case "agent" -> {
                    out.println("  Agent:    " + spec.getExecution().getAgent());
                    out.println("  任务:     " + spec.getExecution().getTask());
                }
                case "composite" -> {
                    out.println("  步骤数:   " + spec.getExecution().getSteps().size());
                }
            }
        }
        out.println();

        if (!spec.getParameters().isEmpty()) {
            out.println("📋 参数:");
            spec.getParameters().forEach(param -> {
                String required = param.isRequired() ? " (必需)" : "";
                String defaultVal = param.getDefaultValue() != null
                        ? " [默认: " + param.getDefaultValue() + "]"
                        : "";
                out.println("  " + param.getName() + " (" + param.getType() + ")"
                        + required + defaultVal);
                if (param.getDescription() != null) {
                    out.println("    " + param.getDescription());
                }
            });
            out.println();
        }

        if (!spec.getPreconditions().isEmpty()) {
            out.println("🔒 前置条件:");
            spec.getPreconditions().forEach(pre ->
                    out.println("  " + pre.getType() + ": "
                            + (pre.getPath() != null ? pre.getPath()
                            : pre.getVar() != null ? pre.getVar()
                            : pre.getCommand())));
            out.println();
        }
    }

    /**
     * 重新加载命令
     */
    private void reloadCommands(CommandContext context, OutputFormatter out) {
        out.printInfo("正在重新加载自定义命令...");
        customCommandRegistry.reloadCommands(context.getEngineClient().getWorkDir());
        out.printSuccess("自定义命令已重新加载 ("
                + customCommandRegistry.size() + " 个)");
    }

    /**
     * 启用命令
     */
    private void enableCommand(String commandName, OutputFormatter out) {
        if (customCommandRegistry.enableCommand(commandName)) {
            out.printSuccess("已启用命令: " + commandName);
        } else {
            out.printError("未找到自定义命令: " + commandName);
        }
    }

    /**
     * 禁用命令
     */
    private void disableCommand(String commandName, OutputFormatter out) {
        if (customCommandRegistry.disableCommand(commandName)) {
            out.printSuccess("已禁用命令: " + commandName);
        } else {
            out.printError("未找到自定义命令: " + commandName);
        }
    }
}
