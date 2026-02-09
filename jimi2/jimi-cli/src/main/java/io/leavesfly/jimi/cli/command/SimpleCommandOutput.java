package io.leavesfly.jimi.cli.command;

import io.leavesfly.jimi.adk.api.command.CommandOutput;

/**
 * SimpleCommandOutput - 简单的命令输出实现
 * <p>
 * 使用 System.out 直接输出，不依赖终端库
 * </p>
 *
 * @author Jimi2 Team
 */
public class SimpleCommandOutput implements CommandOutput {

    @Override
    public void println(String message) {
        System.out.println(message);
    }

    @Override
    public void success(String message) {
        System.out.println("✅ " + message);
    }

    @Override
    public void error(String message) {
        System.err.println("❌ " + message);
    }

    @Override
    public void warn(String message) {
        System.out.println("⚠️  " + message);
    }

    @Override
    public void info(String message) {
        System.out.println("ℹ️  " + message);
    }

    @Override
    public void title(String title) {
        System.out.println("\n=== " + title + " ===");
    }

    @Override
    public void separator() {
        System.out.println("─".repeat(50));
    }
}
