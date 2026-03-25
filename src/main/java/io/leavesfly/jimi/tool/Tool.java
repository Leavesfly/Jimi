package io.leavesfly.jimi.tool;

import reactor.core.publisher.Mono;

/**
 * 工具接口
 * 所有工具必须实现此接口
 * 
 * @param <P> 参数类型
 */
public interface Tool<P> {
    
    /**
     * 获取工具名称
     */
    String getName();
    
    /**
     * 获取工具描述
     */
    String getDescription();
    
    /**
     * 获取参数类型
     */
    Class<P> getParamsType();
    
    /**
     * 执行工具调用
     * 
     * @param params 工具参数
     * @return 工具执行结果的Mono
     */
    Mono<ToolResult> execute(P params);
    
    /**
     * 验证参数（可选，默认不验证）
     * 
     * @param params 工具参数
     * @return 验证是否通过
     */
    default boolean validateParams(P params) {
        return true;
    }

    /**
     * 是否支持并发执行
     * <p>
     * 当 LLM 返回多个工具调用时，标记为并发安全的工具可以并行执行，
     * 从而大幅提升 IO 密集型操作（如文件读取、搜索）的效率。
     * <p>
     * 默认为 true（大多数只读工具是并发安全的）。
     * 文件写入类工具应覆盖此方法返回 false。
     *
     * @return true 表示可以与其他工具并发执行
     */
    default boolean isConcurrentSafe() {
        return true;
    }
}
