package io.leavesfly.jimi.ui.shell.input;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandRegistry;
import io.leavesfly.jimi.ui.shell.ShellContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 元命令输入处理器
 * 处理以 / 开头的元命令
 */
@Slf4j
public class MetaCommandProcessor implements InputProcessor {
    
    private final CommandRegistry commandRegistry;
    
    public MetaCommandProcessor(CommandRegistry commandRegistry) {
        this.commandRegistry = commandRegistry;
    }
    
    @Override
    public boolean canProcess(String input) {
        return input.startsWith("/");
    }
    
    @Override
    public int getPriority() {
        return 10; // 高优先级
    }
    
    @Override
    public boolean process(String input, ShellContext context) throws Exception {
        // 移除 / 前缀
        String commandLine = input.substring(1).trim();
        
        if (commandLine.isEmpty()) {
            context.getOutputFormatter().printError("空命令");
            return true;
        }
        
        // 解析命令名称和参数
        String[] parts = commandLine.split("\\s+", 2);
        String commandName = parts[0];
        String[] args = parts.length > 1 ? parts[1].split("\\s+") : new String[0];
        
        // 特殊处理退出命令
        if (commandName.equals("quit") || commandName.equals("exit")) {
            context.getOutputFormatter().println();
            context.getOutputFormatter().printInfo("Bye!");
            return false; // 退出
        }
        
        // 检查命令是否存在
        if (!commandRegistry.hasCommand(commandName)) {
            context.getOutputFormatter().printError("未知命令: /" + commandName);
            context.getOutputFormatter().printInfo("输入 /help 查看可用命令");
            return true;
        }
        
        // 构建命令上下文
        CommandContext cmdContext = CommandContext.builder()
            .engineClient(context.getEngineClient())
            .terminal(context.getTerminal())
            .lineReader(context.getLineReader())
            .rawInput(input)
            .commandName(commandName)
            .args(args)
            .outputFormatter(context.getOutputFormatter())
            .build();
        
        // 执行命令
        try {
            commandRegistry.execute(commandName, cmdContext);
        } catch (Exception e) {
            log.error("Error executing meta command: /" + commandName, e);
            context.getOutputFormatter().printError("执行命令失败: " + e.getMessage());
        }
        
        return true;
    }
}
