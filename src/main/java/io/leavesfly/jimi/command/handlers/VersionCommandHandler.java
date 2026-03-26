package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /version 命令处理器
 * 显示版本信息
 */
@Component
public class VersionCommandHandler implements CommandHandler {
    
    @Override
    public String getName() {
        return "version";
    }
    
    @Override
    public String getDescription() {
        return "显示版本信息";
    }
    
    @Override
    public List<String> getAliases() {
        return List.of("v");
    }
    
    @Override
    public void execute(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        out.println();
        out.printSuccess("Jimi");
        out.println("  Version: 0.1.0");
        out.println("  Java Version: " + System.getProperty("java.version"));
        out.println("  JimiRuntime: " + System.getProperty("java.runtime.name"));
        out.println();
    }
}
