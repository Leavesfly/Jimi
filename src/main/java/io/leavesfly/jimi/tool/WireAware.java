package io.leavesfly.jimi.tool;

import io.leavesfly.jimi.wire.Wire;

/**
 * Wire 感知接口
 * 工具实现此接口以接收 Wire 消息总线的注入
 * 
 * 设计目的：
 * - 消除高层模块对具体工具类的依赖（避免 instanceof 检查）
 * - 遵循依赖倒置原则（面向接口编程）
 * - 支持开闭原则（新增需要 Wire 的工具无需修改核心代码）
 */
public interface WireAware {
    
    /**
     * 设置 Wire 消息总线
     * 
     * @param wire Wire 消息总线实例
     */
    void setWire(Wire wire);
}
