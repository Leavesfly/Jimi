package io.leavesfly.jimi.ui.shell.input;

import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.ui.shell.ShellContext;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
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
            // 检查 BashTool 工具是否可用
            if (!context.getEngineClient().hasTool("BashTool")) {
                out.printError("BashTool 工具不可用");
                return true;
            }
            
            // 构造 BashTool 工具参数（JSON 格式）
            String arguments = String.format(
                "{\"command\":\"%s\",\"timeout\":60}",
                jsonEscape(shellCommand)
            );
            
            // 执行 BashTool 工具
            ToolResult result = context.getEngineClient()
                .executeTool("BashTool", arguments)
                .block();
            
            if (result == null) {
                out.printError("执行命令失败: 无返回结果");
                return true;
            }
            
            // 显示结果
            if (result.isOk()) {
                out.printSuccess("命令执行成功");
                if (!result.getOutput().isEmpty()) {
                    out.println();
                    out.println(result.getOutput());
                }
            } else if (result.isError()) {
                out.printError("命令执行失败: " + result.getMessage());
                if (!result.getOutput().isEmpty()) {
                    out.println();
                    out.println(result.getOutput());
                }
            } else {
                // REJECTED
                out.printError("命令被用户拒绝");
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
