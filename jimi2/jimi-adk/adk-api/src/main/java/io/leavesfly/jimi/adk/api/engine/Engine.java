package io.leavesfly.jimi.adk.api.engine;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.wire.Wire;
import reactor.core.publisher.Mono;

/**
 * 引擎接口 - AI Agent 的核心执行引擎
 */
public interface Engine {
    
    /**
     * 使用用户输入运行引擎
     *
     * @param input 用户输入
     * @return 执行结果
     */
    Mono<ExecutionResult> run(String input);
    
    /**
     * 使用用户输入和额外上下文运行引擎
     *
     * @param input 用户输入
     * @param additionalContext 额外上下文信息
     * @return 执行结果
     */
    Mono<ExecutionResult> run(String input, String additionalContext);
    
    /**
     * 获取当前 Agent
     *
     * @return Agent 实例
     */
    Agent getAgent();
    
    /**
     * 获取运行时环境
     *
     * @return 运行时环境
     */
    Runtime getRuntime();
    
    /**
     * 获取上下文
     *
     * @return 上下文
     */
    Context getContext();
    
    /**
     * 获取工具注册表
     *
     * @return 工具注册表
     */
    ToolRegistry getToolRegistry();
    
    /**
     * 获取消息总线
     *
     * @return Wire 消息总线
     */
    Wire getWire();
    
    /**
     * 检查引擎是否正在运行
     *
     * @return 是否正在运行
     */
    boolean isRunning();
    
    /**
     * 中断当前执行
     */
    void interrupt();
}
