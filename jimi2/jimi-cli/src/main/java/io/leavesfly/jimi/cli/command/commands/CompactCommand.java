package io.leavesfly.jimi.cli.command.commands;

import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;

/**
 * Compact 命令 - 压缩上下文
 * <p>
 * 触发上下文压缩，减少 Token 使用
 * </p>
 *
 * @author Jimi2 Team
 */
public class CompactCommand implements Command {

    private final Engine engine;

    public CompactCommand(Engine engine) {
        this.engine = engine;
    }

    @Override
    public String getName() {
        return "compact";
    }

    @Override
    public String getDescription() {
        return "Compact conversation context";
    }

    @Override
    public String getCategory() {
        return "system";
    }

    @Override
    public void execute(CommandContext context) {
        var output = context.getOutput();

        output.title("Compact Context");
        output.separator();

        if (engine == null || engine.getContext() == null) {
            output.warn("Engine or context not available");
            output.println("");
            output.info("Context compaction requires an active conversation session");
            return;
        }

        try {
            int beforeSize = engine.getContext().getHistory().size();
            int beforeTokens = engine.getContext().getTokenCount();

            output.info("Current context:");
            output.println("  Messages: " + beforeSize);
            output.println("  Tokens: " + beforeTokens);
            output.println("");

            // 检查是否需要压缩
            if (beforeSize <= 10) {
                output.info("Context is small, no need to compact");
                return;
            }

            output.info("Context compaction will be triggered automatically");
            output.println("");
            output.println("Tips:");
            output.println("  - Compaction happens automatically when context grows large");
            output.println("  - Important messages are preserved");
            output.println("  - You can use /reset to clear all context");

        } catch (Exception e) {
            output.error("Failed to analyze context: " + e.getMessage());
        }
    }
}
