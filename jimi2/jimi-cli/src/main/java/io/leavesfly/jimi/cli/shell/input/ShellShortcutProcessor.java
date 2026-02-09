package io.leavesfly.jimi.cli.shell.input;

import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.cli.shell.ShellContext;
import io.leavesfly.jimi.cli.shell.output.OutputFormatter;
import lombok.extern.slf4j.Slf4j;

/**
 * Shell 快捷方式输入处理器
 * 处理以 ! 开头的 Shell 命令
 */
@Slf4j
public class ShellShortcutProcessor implements InputProcessor {
    
    @Override
    public boolean canProcess(String input) {
        return input.startsWith("!");
    }
    
    @Override
    public int getPriority() {
        return 20; // 中等优先级
    }
    
    @Override
    public boolean process(String input, ShellContext context) throws Exception {
        OutputFormatter out = context.getOutputFormatter();
        
        String shellCommand = input.substring(1).trim();
        
        if (shellCommand.isEmpty()) {
            out.printError("! 后面没有指定命令");
            return true;
        }
        
        out.printInfo("执行 Shell 命令: " + shellCommand);
        
        try {
            // 检查 Bash 工具是否可用
            if (!context.getToolRegistry().hasTool("Bash")) {
                out.printError("Bash 工具不可用");
                return true;
            }
            
            // 构造 Bash 工具参数（JSON 格式）
            String arguments = String.format(
                "{\"command\":\"%s\",\"timeout\":60}",
                jsonEscape(shellCommand)
            );
            
            // 执行 Bash 工具
            ToolResult result = context.getToolRegistry()
                .execute("Bash", arguments)
                .block();
            
            if (result == null) {
                out.printError("执行命令失败: 无返回结果");
                return true;
            }
            
            // 显示结果
            if (result.isOk()) {
                out.printSuccess("命令执行成功");
                String message = result.getMessage();
                if (message != null && !message.isEmpty()) {
                    out.println();
                    out.println(message);
                }
            } else {
                out.printError("命令执行失败: " + result.getError());
                String message = result.getMessage();
                if (message != null && !message.isEmpty()) {
                    out.println();
                    out.println(message);
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to execute shell command", e);
            out.printError("执行命令失败: " + e.getMessage());
        }
        
        return true;
    }
    
    /**
     * JSON 字符串转义
     */
    private String jsonEscape(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
