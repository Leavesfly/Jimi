package io.leavesfly.jimi.core.engine;

/**
 * Engine 相关常量定义
 * 
 * 统一管理 Engine 模块使用的常量，避免重复定义
 */
public final class EngineConstants {

    /**
     * 预留 Token 数量
     * <p>
     * 用途：
     * 1. AgentExecutor: 作为触发上下文压缩的安全阈值
     * 2. JimiEngine: 计算可用 Token 数量供状态展示
     * <p>
     * 说明：预留足够的Token空间确保即使在压缩触发前，
     * LLM仍有足够空间生成完整的输出和工具调用，避免因上下文满载导致的调用失败
     */
    public static final int RESERVED_TOKENS = 20_000;

    /**
     * 私有构造函数，防止实例化
     */
    private EngineConstants() {
        throw new AssertionError("EngineConstants is a utility class and should not be instantiated");
    }
}
