package io.leavesfly.jimi.adk.tools.extended.meta;

/**
 * ToolBridge 持有者
 * 
 * 用于在 JShell 环境中访问 ToolBridge 实例
 * 
 * 注意：使用静态变量而不是 ThreadLocal，因为 JShell 可能在不同线程中执行
 * 但我们在同一个 JVM 中，所以静态变量可以安全共享
 */
public class ToolBridgeHolder {
    // 使用 volatile 确保可见性
    private static volatile ToolBridge currentBridge = null;
    
    public static void set(ToolBridge bridge) {
        currentBridge = bridge;
    }
    
    public static ToolBridge get() {
        return currentBridge;
    }
    
    public static void clear() {
        currentBridge = null;
    }
}
