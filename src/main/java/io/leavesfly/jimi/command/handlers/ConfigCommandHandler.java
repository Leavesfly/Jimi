package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import org.springframework.stereotype.Component;

/**
 * /config 命令处理器
 * 显示配置信息
 */
@Component
public class ConfigCommandHandler implements CommandHandler {
    
    @Override
    public String getName() {
        return "config";
    }
    
    @Override
    public String getDescription() {
        return "显示配置信息";
    }
    
    @Override
    public void execute(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        out.println();
        out.printSuccess("配置信息:");
        
        // LLM 信息
        if (context.getSoul().getRuntime().getLlm() != null) {
            out.println("  LLM: ✅ 已配置");
        } else {
            out.println("  LLM: ❌ 未配置");
            out.printInfo("请设置 KIMI_API_KEY 环境变量");
        }
        
        // 工作目录
        out.println("  工作目录: " + context.getSoul().getRuntime().getBuiltinArgs().getJimiWorkDir());
        
        // 会话信息
        out.println("  会话 ID: " + context.getSoul().getRuntime().getSession().getId());
        out.println("  历史文件: " + context.getSoul().getRuntime().getSession().getHistoryFile());
        
        // YOLO 模式
        boolean yolo = context.getSoul().getRuntime().getApproval().isYolo();
        out.println("  YOLO 模式: " + (yolo ? "✅ 开启" : "❌ 关闭"));
        
        out.println();
    }
}
