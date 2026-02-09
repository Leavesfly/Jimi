package io.leavesfly.jimi.cli.command.commands;

import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;
import io.leavesfly.jimi.cli.command.CommandRegistry;

import java.util.List;
import java.util.Map;

/**
 * Help 命令 - 显示帮助信息
 *
 * @author Jimi2 Team
 */
public class HelpCommand implements Command {

    private final CommandRegistry registry;

    public HelpCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getDescription() {
        return "Display help information";
    }

    @Override
    public List<String> getAliases() {
        return List.of("h", "?");
    }

    @Override
    public String getUsage() {
        return "/help [command]";
    }

    @Override
    public String getCategory() {
        return "system";
    }

    @Override
    public void execute(CommandContext context) {
        if (context.getArgCount() == 0) {
            showAllCommands(context);
        } else {
            showCommandHelp(context, context.getArg(0));
        }
    }

    private void showAllCommands(CommandContext context) {
        var output = context.getOutput();
        output.title("Available Commands");
        output.separator();

        Map<String, List<Command>> categorized = registry.getCommandsByCategory();

        for (Map.Entry<String, List<Command>> entry : categorized.entrySet()) {
            String category = entry.getKey();
            List<Command> commands = entry.getValue();

            output.println("");
            output.info(category.toUpperCase());

            for (Command cmd : commands) {
                String aliases = cmd.getAliases().isEmpty() ? "" :
                        " (aliases: " + String.join(", ", cmd.getAliases()) + ")";
                output.println(String.format("  /%s%s - %s",
                        cmd.getName(), aliases, cmd.getDescription()));
            }
        }

        output.println("");
        output.info("Type /help <command> for detailed information");
    }

    private void showCommandHelp(CommandContext context, String commandName) {
        Command command = registry.find(commandName);

        if (command == null) {
            context.getOutput().error("Unknown command: " + commandName);
            return;
        }

        var output = context.getOutput();
        output.title("Command: " + command.getName());
        output.separator();
        output.println("Description: " + command.getDescription());
        output.println("Usage: " + command.getUsage());

        if (!command.getAliases().isEmpty()) {
            output.println("Aliases: " + String.join(", ", command.getAliases()));
        }

        output.println("Category: " + command.getCategory());
    }
}
