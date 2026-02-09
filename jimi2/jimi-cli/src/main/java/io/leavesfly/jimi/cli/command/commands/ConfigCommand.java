package io.leavesfly.jimi.cli.command.commands;

import io.leavesfly.jimi.adk.api.command.Command;
import io.leavesfly.jimi.adk.api.command.CommandContext;

import java.util.Map;

/**
 * Config 命令 - 配置管理
 * <p>
 * 显示和管理系统配置（简化版）
 * </p>
 *
 * @author Jimi2 Team
 */
public class ConfigCommand implements Command {

    private final Map<String, String> config;

    public ConfigCommand(Map<String, String> config) {
        this.config = config;
    }

    @Override
    public String getName() {
        return "config";
    }

    @Override
    public String getDescription() {
        return "Show configuration";
    }

    @Override
    public String getCategory() {
        return "system";
    }

    @Override
    public String getUsage() {
        return "/config [key]";
    }

    @Override
    public void execute(CommandContext context) {
        var output = context.getOutput();

        if (context.getArgCount() == 0) {
            // 显示所有配置
            showAllConfig(output);
        } else {
            // 显示指定配置
            String key = context.getArg(0);
            showConfig(output, key);
        }
    }

    private void showAllConfig(io.leavesfly.jimi.adk.api.command.CommandOutput output) {
        output.title("Configuration");
        output.separator();

        if (config == null || config.isEmpty()) {
            output.warn("No configuration available");
            return;
        }

        for (Map.Entry<String, String> entry : config.entrySet()) {
            String value = entry.getValue();
            // 隐藏敏感信息
            if (entry.getKey().toLowerCase().contains("key") ||
                entry.getKey().toLowerCase().contains("password") ||
                entry.getKey().toLowerCase().contains("token")) {
                value = maskSensitive(value);
            }
            output.println(String.format("  %s = %s", entry.getKey(), value));
        }
    }

    private void showConfig(io.leavesfly.jimi.adk.api.command.CommandOutput output, String key) {
        if (config == null || !config.containsKey(key)) {
            output.error("Configuration key not found: " + key);
            return;
        }

        String value = config.get(key);
        if (key.toLowerCase().contains("key") ||
            key.toLowerCase().contains("password") ||
            key.toLowerCase().contains("token")) {
            value = maskSensitive(value);
        }

        output.println(key + " = " + value);
    }

    private String maskSensitive(String value) {
        if (value == null || value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
