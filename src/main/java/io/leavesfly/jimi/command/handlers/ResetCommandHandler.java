package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import io.leavesfly.jimi.wire.message.request.ContextQueryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * /reset 命令处理器
 * 清除上下文历史
 */
@Slf4j
@Component
public class ResetCommandHandler implements CommandHandler {
    
    @Override
    public String getName() {
        return "reset";
    }
    
    @Override
    public String getDescription() {
        return "清除上下文历史";
    }
    
    @Override
    public void execute(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        try {
            int checkpoints = context.getEngineClient().getContextInfo().getCheckpointCount();
            
            if (checkpoints == 0) {
                out.printInfo("上下文已经为空");
                return;
            }
            
            // 回退到最初状态
            context.getEngineClient().resetContext().block();
            
            out.printSuccess("✅ 上下文已清除");
            out.printInfo("已回退到初始状态，所有历史消息已清空");
            
        } catch (Exception e) {
            log.error("Failed to reset context", e);
            out.printError("清除上下文失败: " + e.getMessage());
        }
    }
}