package io.leavesfly.jimi.cli.command.commands;

import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;

import java.util.List;

/**
 * Clear 命令 - 清屏
 *
 * @author Jimi2 Team
 */
public class ClearCommand implements Command {

    @Override
    public String getName() {
        return "clear";
    }

    @Override
    public String getDescription() {
        return "Clear the screen";
    }

    @Override
    public List<String> getAliases() {
        return List.of("cls");
    }

    @Override
    public String getCategory() {
        return "system";
    }

    @Override
    public void execute(CommandContext context) {
        context.getOutput().clearScreen();
    }
}
