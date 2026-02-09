package io.leavesfly.jimi.cli.shell.input;

import io.leavesfly.jimi.cli.shell.ShellContext;

/**
 * 输入处理器接口
 * 用于处理不同类型的用户输入
 */
public interface InputProcessor {
    
    /**
     * 检查是否可以处理该输入
     * 
     * @param input 用户输入
     * @return 如果可以处理返回 true
     */
    boolean canProcess(String input);
    
    /**
     * 处理用户输入
     * 
     * @param input 用户输入
     * @param context Shell上下文
     * @return 是否继续运行（true 继续，false 退出）
     * @throws Exception 处理过程中的异常
     */
    boolean process(String input, ShellContext context) throws Exception;
    
    /**
     * 获取处理器的优先级
     * 数值越小优先级越高
     * 
     * @return 优先级
     */
    default int getPriority() {
        return 100;
    }
}
