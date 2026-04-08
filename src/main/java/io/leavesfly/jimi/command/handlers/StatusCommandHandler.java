package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import io.leavesfly.jimi.wire.message.request.ContextQueryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * /status 命令处理器
 * 显示系统状态信息
 */
@Slf4j
@Component
public class StatusCommandHandler implements CommandHandler {
    
    @Override
    public String getName() {
        return "status";
    }
    
    @Override
    public String getDescription() {
        return "显示当前状态";
    }
    
    @Override
    public void execute(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        out.println();
        out.printSuccess("系统状态:");
        
        // Agent 信息
        out.println("  Agent: " + context.getEngineClient().getAgentName());
        
        // 工具数量
        out.println("  可用工具数: " + context.getEngineClient().getToolNames().size());
        
        // 上下文信息
        try {
            ContextQueryRequest.ContextInfo contextInfo = context.getEngineClient().getContextInfo();
            int messageCount = contextInfo.getHistorySize();
            int tokenCount = contextInfo.getTokenCount();
            out.println("  上下文消息数: " + messageCount);
            out.println("  上下文 Token 数: " + tokenCount);
        } catch (Exception e) {
            log.debug("Failed to get context info", e);
        }
        
        out.println();
    }
}