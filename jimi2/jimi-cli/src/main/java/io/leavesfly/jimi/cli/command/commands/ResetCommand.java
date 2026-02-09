package io.leavesfly.jimi.cli.command.commands;

import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;

/**
 * Reset 命令 - 重置对话上下文
 * <p>
 * 注意：需要 Engine 支持才能实际清空上下文
 * </p>
 *
 * @author Jimi2 Team
 */
public class ResetCommand implements Command {

    private final Engine engine;

    public ResetCommand(Engine engine) {
        this.engine = engine;
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
            // 重置上下文（清空历史消息）
            if (engine != null && engine.getContext() != null) {
                engine.getContext().clear();
                output.success("Context reset successfully");
            } else {
                output.warn("Engine or context not available");
            }
        } catch (Exception e) {
            output.error("Failed to reset context: " + e.getMessage());
        }
    }
}
