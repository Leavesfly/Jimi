package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /help 命令处理器
 * 显示帮助信息
 */
@Component
public class HelpCommandHandler implements CommandHandler {
    
    @Override
    public String getName() {
        return "help";
    }
    
    @Override
    public String getDescription() {
        return "显示帮助信息";
    }
    
    @Override
    public List<String> getAliases() {
        return List.of("h", "?");
    }
    
    @Override
    public void execute(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        out.println();
        out.println("┌────────────────────────────────────────────────────────────┐");
        out.println("│                     Jimi CLI Help                          │");
        out.println("└────────────────────────────────────────────────────────────┘");
        out.println();
        
        out.printSuccess("基本命令:");
        out.println("  exit, quit      - 退出 Jimi");
        out.println("  ! <command>     - 直接运行 Shell 命令（需审批）");
        out.println();
        
        out.printSuccess("元命令 (Meta Commands):");
        out.println("  /help, /h, /?   - 显示帮助信息");
        out.println("  /quit, /exit    - 退出程序");
        out.println("  /version, /v    - 显示版本信息");
        out.println("  /status         - 显示当前状态");
        out.println("  /config         - 显示配置信息");
        out.println("  /tools          - 显示可用工具列表");
        out.println("  /init           - 分析代码库并生成 AGENTS.md");
        out.println("  /graph, /g      - 代码图管理 (build|stats|clear)");
        out.println("  /clear, /cls    - 清屏");
        out.println("  /history        - 显示命令历史");
        out.println("  /reset          - 清除上下文历史");
        out.println("  /compact        - 压缩上下文");
        out.println("  /agents         - [agent-name | run <agent-name>]");
        out.println();
        
        out.printSuccess("Shell 快捷方式:");
        out.println("  ! ls -la        - 执行 Shell 命令");
        out.println("  ! pwd           - 显示当前目录");
        out.println("  ! mvn test      - 运行 Maven 测试");
        out.println();
        
        out.printInfo("或者直接输入你的问题，让 Jimi 帮助你！");
        out.println();
    }
}
