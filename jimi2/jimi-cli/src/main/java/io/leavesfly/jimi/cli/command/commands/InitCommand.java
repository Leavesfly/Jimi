package io.leavesfly.jimi.cli.command.commands;

import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Init 命令 - 初始化代码库
 * <p>
 * 分析项目并生成 AGENTS.md 文档（简化版）
 * </p>
 *
 * @author Jimi2 Team
 */
public class InitCommand implements Command {

    private final Engine engine;

    public InitCommand(Engine engine) {
        this.engine = engine;
    }

    @Override
    public String getName() {
        return "init";
    }

    @Override
    public String getDescription() {
        return "Initialize codebase and generate AGENTS.md";
    }

    @Override
    public String getCategory() {
        return "general";
    }

    @Override
    public void execute(CommandContext context) {
        var output = context.getOutput();

        // 检查工作目录
        Path workDir = context.getRuntime().getWorkDir();
        Path agentsMd = workDir.resolve("AGENTS.md");

        output.title("Initialize Codebase");
        output.separator();

        // 检查是否已存在
        if (Files.exists(agentsMd)) {
            output.warn("AGENTS.md already exists");
            output.println("Current file: " + agentsMd);
            output.println("");
            output.info("To regenerate, please delete the file first or ask the agent to analyze the codebase");
            return;
        }

        // 如果 Engine 可用，提示运行分析
        if (engine != null) {
            output.info("Ready to analyze codebase");
            output.println("");
            output.println("You can ask the agent:");
            output.println("  \"Please analyze this codebase and create AGENTS.md\"");
            output.println("");
            output.println("Or provide a custom prompt for specific analysis focus.");
        } else {
            output.warn("Engine not available");
            output.println("");
            output.info("To use this command, start a conversation session with the agent");
        }

        output.println("");
        output.println("Working directory: " + workDir);
    }
}
