package io.leavesfly.jimi.adk.api.command;

import java.util.List;

/**
 * Command SPI 接口
 * <p>
 * 所有前端命令处理器（CLI、IDE 插件、Web）都需要实现此接口。
 * 支持命令优先级、分类、别名等高级特性。
 * </p>
 * <p>
 * 架构定位：作为 ADK API 层的 SPI 契约，允许各前端模块
 * 提供自己的命令实现，而不局限于 CLI。
 * </p>
 *
 * @see CommandContext 命令执行上下文
 * @see CommandOutput 命令输出抽象
 */
public interface Command {

    /**
     * 获取命令名称
     *
     * @return 命令名称（不含 / 前缀）
     */
    String getName();

    /**
     * 获取命令描述
     *
     * @return 命令的简短描述
     */
    String getDescription();

    /**
     * 获取命令别名列表
     *
     * @return 别名列表，如果没有别名则返回空列表
     */
    default List<String> getAliases() {
        return List.of();
    }

    /**
     * 获取命令用法说明
     *
     * @return 用法说明字符串
     */
    default String getUsage() {
        return "/" + getName();
    }

    /**
     * 获取命令优先级
     * 数值越大优先级越高
     *
     * @return 优先级（默认 0）
     */
    default int getPriority() {
        return 0;
    }

    /**
     * 获取命令分类
     * 用于命令的分组显示
     *
     * @return 分类名称（默认 "general"）
     */
    default String getCategory() {
        return "general";
    }

    /**
     * 执行命令
     *
     * @param context 命令执行上下文
     * @throws Exception 执行过程中的异常
     */
    void execute(CommandContext context) throws Exception;

    /**
     * 检查命令是否可用
     * 某些命令可能在特定条件下不可用
     *
     * @param context 命令执行上下文
     * @return 如果命令可用返回 true
     */
    default boolean isAvailable(CommandContext context) {
        return true;
    }
}
