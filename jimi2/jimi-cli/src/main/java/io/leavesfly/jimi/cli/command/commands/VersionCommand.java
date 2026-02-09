package io.leavesfly.jimi.cli.command.commands;

import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;

import java.util.List;

/**
 * Version 命令 - 显示版本信息
 *
 * @author Jimi2 Team
 */
public class VersionCommand implements Command {

    private final String version;

    public VersionCommand(String version) {
        this.version = version;
    }

    @Override
    public String getName() {
        return "version";
    }

    @Override
    public String getDescription() {
        return "Display version information";
    }

    @Override
    public List<String> getAliases() {
        return List.of("v");
    }

    @Override
    public String getCategory() {
        return "system";
    }

    @Override
    public void execute(CommandContext context) {
        var output = context.getOutput();
        output.title("Jimi Version");
        output.separator();
        output.println("Version: " + version);
        output.println("Java Version: " + System.getProperty("java.version"));
        output.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
    }
}
