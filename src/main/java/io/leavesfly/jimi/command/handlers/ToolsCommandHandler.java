package io.leavesfly.jimi.command.handlers;

import io.leavesfly.jimi.command.CommandContext;
import io.leavesfly.jimi.command.CommandHandler;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * /tools 命令处理器
 * 显示可用工具列表
 */
@Component
public class ToolsCommandHandler implements CommandHandler {
    
    @Override
    public String getName() {
        return "tools";
    }
    
    @Override
    public String getDescription() {
        return "显示可用工具列表";
    }
    
    @Override
    public void execute(CommandContext context) {
        OutputFormatter out = context.getOutputFormatter();
        
        out.println();
        out.printSuccess("可用工具列表:");
        
        List<String> toolNames = new ArrayList<>(context.getEngineClient().getToolNames());
        toolNames.sort(String::compareTo);
        
        // 按类别分组
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("文件操作", new ArrayList<>());
        categories.put("Shell", new ArrayList<>());
        categories.put("Web", new ArrayList<>());
        categories.put("其他", new ArrayList<>());
        
        for (String toolName : toolNames) {
            String lowerName = toolName.toLowerCase();
            if (lowerName.contains("file") || 
                lowerName.contains("read") || 
                lowerName.contains("write") ||
                lowerName.contains("grep") ||
                lowerName.contains("glob")) {
                categories.get("文件操作").add(toolName);
            } else if (lowerName.contains("bash") || 
                       lowerName.contains("shell")) {
                categories.get("Shell").add(toolName);
            } else if (lowerName.contains("web") || 
                       lowerName.contains("fetch") ||
                       lowerName.contains("search")) {
                categories.get("Web").add(toolName);
            } else {
                categories.get("其他").add(toolName);
            }
        }
        
        // 打印分组
        for (Map.Entry<String, List<String>> entry : categories.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                out.println();
                out.printInfo(entry.getKey() + ":");
                for (String tool : entry.getValue()) {
                    out.println("  • " + tool);
                }
            }
        }
        
        out.println();
        out.println("总计: " + toolNames.size() + " 个工具");
        out.println();
    }
}
