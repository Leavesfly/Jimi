package io.leavesfly.jimi.adk.api.command;

import io.leavesfly.jimi.adk.api.engine.Runtime;
import lombok.Builder;
import lombok.Getter;

/**
 * CommandContext - 命令执行上下文
 * <p>
 * 包含命令执行所需的所有信息和依赖
 * </p>
 *
 * @author Jimi2 Team
 */
@Getter
@Builder
public class CommandContext {

    /**
     * Runtime 运行时上下文
     */
    private final Runtime runtime;

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
     * 输出接口（用于打印结果）
     */
    private final CommandOutput output;

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
