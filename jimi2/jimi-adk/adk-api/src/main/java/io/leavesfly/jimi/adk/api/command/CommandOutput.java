package io.leavesfly.jimi.adk.api.command;

/**
 * CommandOutput - 命令输出接口
 * <p>
 * 提供统一的命令输出抽象，支持不同的终端实现
 * </p>
 *
 * @author Jimi2 Team
 */
public interface CommandOutput {

    /**
     * 打印普通消息
     *
     * @param message 消息内容
     */
    void println(String message);

    /**
     * 打印成功消息
     *
     * @param message 消息内容
     */
    void success(String message);

    /**
     * 打印错误消息
     *
     * @param message 消息内容
     */
    void error(String message);

    /**
     * 打印警告消息
     *
     * @param message 消息内容
     */
    void warn(String message);

    /**
     * 打印信息消息
     *
     * @param message 消息内容
     */
    void info(String message);

    /**
     * 打印标题
     *
     * @param title 标题内容
     */
    void title(String title);

    /**
     * 打印分隔线
     */
    void separator();

    /**
     * 清屏
     */
    default void clearScreen() {
        // 默认实现：打印换行
        for (int i = 0; i < 50; i++) {
            println("");
        }
    }

    /**
     * 打印表格行
     *
     * @param columns 列内容
     */
    default void printTableRow(String... columns) {
        println(String.join(" | ", columns));
    }
}
