package io.leavesfly.jimi.cli.command.commands;

import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;

import java.util.List;

/**
 * Tools 命令 - 显示可用工具列表
 *
 * @author Jimi2 Team
 */
public class ToolsCommand implements Command {

    private final List<Tool<?>> tools;

    public ToolsCommand(List<Tool<?>> tools) {
        this.tools = tools;
    }

    @Override
    public String getName() {
        return "tools";
    }

    @Override
    public String getDescription() {
        return "List available tools";
    }

    @Override
    public String getCategory() {
        return "general";
    }

    @Override
    public void execute(CommandContext context) {
        var output = context.getOutput();
        output.title("Available Tools");
        output.separator();

        if (tools.isEmpty()) {
            output.warn("No tools available");
            return;
        }

        for (Tool<?> tool : tools) {
            output.println(String.format("  %s - %s", tool.getName(), tool.getDescription()));
        }

        output.println("");
        output.info("Total: " + tools.size() + " tools");
    }
}
