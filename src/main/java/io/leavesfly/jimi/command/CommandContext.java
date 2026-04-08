package io.leavesfly.jimi.command;

import io.leavesfly.jimi.client.EngineClient;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import lombok.Builder;
import lombok.Getter;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

/**
 * 命令执行上下文
 * 包含命令执行所需的所有信息和依赖
 */
@Getter
@Builder
public class CommandContext {
    
    /**
     * EngineClient 实例
     */
    private final EngineClient engineClient;
    
    /**
     * 终端实例
     */
    private final Terminal terminal;
    
    /**
     * LineReader 实例
     */
    private final LineReader lineReader;
    
    /**
     * 原始输入字符串
     */
    private final String rawInput;
    
    /**
     * 命令名称（不含 / 前缀）
     */
    private final String commandName;
    
    /**
     * 命令参数数组
     */
    private final String[] args;
    
    /**
     * 输出格式化器
     */
    private final OutputFormatter outputFormatter;

    /**
     * 获取完整的参数字符串
     * 
     * @return 参数字符串，如果没有参数则返回空字符串
     */
    public String getArgsAsString() {
        if (args == null || args.length == 0) {
            return "";
        }
        return String.join(" ", args);
    }
    
    /**
     * 获取指定索引的参数
     * 
     * @param index 参数索引（从 0 开始）
     * @return 参数值，如果索引越界则返回 null
     */
    public String getArg(int index) {
        if (args == null || index < 0 || index >= args.length) {
            return null;
        }
        return args[index];
    }
    
    /**
     * 获取参数数量
     * 
     * @return 参数数量
     */
    public int getArgCount() {
        return args == null ? 0 : args.length;
    }
}
