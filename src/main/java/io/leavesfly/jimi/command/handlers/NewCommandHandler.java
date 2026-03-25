package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * /new 命令处理器
 * 开启新会话
 */
@Slf4j
@Component
public class NewCommandHandler implements CommandHandler {
    
    @Override
    public String getName() {
        return "new";
    }
    
    @Override
    public String getDescription() {
        return "开启新会话";
    }
    
    @Override
    public void execute(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        try {
            String oldSessionId = context.getEngineClient().getSessionId();
            
            // 创建新会话
            context.getEngineClient().newSession().block();
            
            String newSessionId = context.getEngineClient().getSessionId();
            
            out.printSuccess("✅ 新会话已创建");
            out.printInfo("  旧会话: " + oldSessionId);
            out.printInfo("  新会话: " + newSessionId);
            out.printInfo("上下文已清空，可以开始新的对话");
            
        } catch (Exception e) {
            log.error("Failed to create new session", e);
            out.printError("创建新会话失败: " + e.getMessage());
        }
    }
}
