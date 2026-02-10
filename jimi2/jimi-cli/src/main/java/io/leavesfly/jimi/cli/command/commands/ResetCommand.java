package io.leavesfly.jimi.cli.command.commands;

import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;

/**
 * Reset 命令 - 重置对话上下文
 *
 * @author Jimi2 Team
 */
public class ResetCommand implements Command {

    private final Context adkContext;

    public ResetCommand(Context adkContext) {
        this.adkContext = adkContext;
    }

    @Override
    public String getName() {
        return "reset";
    }

    @Override
    public String getDescription() {
        return "Reset conversation context";
    }

    @Override
    public String getCategory() {
        return "system";
    }

    @Override
    public void execute(CommandContext context) {
        var output = context.getOutput();

        try {
            if (adkContext != null) {
                adkContext.clear();
                output.success("Context reset successfully");
            } else {
                output.warn("Context not available");
            }
        } catch (Exception e) {
            output.error("Failed to reset context: " + e.getMessage());
        }
    }
}
