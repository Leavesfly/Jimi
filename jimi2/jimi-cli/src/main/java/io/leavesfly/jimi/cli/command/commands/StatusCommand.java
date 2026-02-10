package io.leavesfly.jimi.cli.command.commands;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;

import java.util.List;

/**
 * Status 命令 - 显示系统状态
 *
 * @author Jimi2 Team
 */
public class StatusCommand implements Command {

    private final Engine engine;
    private final Context adkContext;
    private final List<Tool<?>> tools;

    public StatusCommand(Engine engine, Context adkContext, List<Tool<?>> tools) {
        this.engine = engine;
        this.adkContext = adkContext;
        this.tools = tools;
    }

    @Override
    public String getName() {
        return "status";
    }

    @Override
    public String getDescription() {
        return "Show system status";
    }

    @Override
    public String getCategory() {
        return "system";
    }

    @Override
    public void execute(CommandContext context) {
        var output = context.getOutput();

        output.title("System Status");
        output.separator();

        // Agent 信息
        if (engine != null && engine.getAgent() != null) {
            Agent agent = engine.getAgent();
            output.println("Agent: " + agent.getName());
            output.println("Max Steps: " + agent.getMaxSteps());

            if (agent.getTools() != null) {
                output.println("Loaded Tools: " + agent.getTools().size());
            }
        } else {
            output.warn("Engine not available");
        }

        // 工具统计
        if (tools != null) {
            output.println("Available Tools: " + tools.size());
        }

        // 上下文信息
        if (adkContext != null) {
            try {
                int messageCount = adkContext.getHistory().size();
                int tokenCount = adkContext.getTokenCount();
                output.println("Context Messages: " + messageCount);
                output.println("Context Tokens: " + tokenCount);
            } catch (Exception e) {
                output.warn("Unable to get context info: " + e.getMessage());
            }
        }

        // 运行时信息
        if (context.getRuntime() != null) {
            output.println("Work Directory: " + context.getRuntime().getConfig().getWorkDir());
        }

        output.println("");
        output.info("Runtime: Java " + System.getProperty("java.version"));
    }
}
