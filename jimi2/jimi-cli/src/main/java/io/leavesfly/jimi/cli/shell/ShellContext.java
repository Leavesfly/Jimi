package io.leavesfly.jimi.cli.shell;

import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.cli.command.CommandRegistry;
import io.leavesfly.jimi.cli.shell.output.OutputFormatter;
import lombok.Builder;
import lombok.Getter;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;


@Getter
@Builder
public class ShellContext {

    /**
     * 执行引擎
     */
    private final Engine engine;

    /**
     * 运行时上下文
     */
    private final Runtime runtime;

    /**
     * 工具注册表
     */
    private final ToolRegistry toolRegistry;

    /**
     * 命令注册表
     */
    private final CommandRegistry commandRegistry;

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
     * 输出格式化器
     */
    private final OutputFormatter outputFormatter;
}
