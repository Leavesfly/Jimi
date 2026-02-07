package io.leavesfly.jimi.adk.core.engine;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.wire.Wire;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认引擎实现
 * 协调 Agent 执行、工具调用和上下文管理
 */
@Slf4j
@Getter
public class DefaultEngine implements Engine {
    
    /**
     * Agent 实例
     */
    private final Agent agent;
    
    /**
     * 运行时环境
     */
    private final Runtime runtime;
    
    /**
     * 上下文
     */
    private final Context context;
    
    /**
     * 工具注册表
     */
    private final ToolRegistry toolRegistry;
    
    /**
     * 消息总线
     */
    private final Wire wire;
    
    /**
     * Agent 执行器
     */
    private final AgentExecutor executor;
    
    /**
     * 是否正在运行
     */
    private final AtomicBoolean running;
    
    /**
     * 是否被中断
     */
    private final AtomicBoolean interrupted;
    
    @Builder
    public DefaultEngine(Agent agent, Runtime runtime, Context context,
                        ToolRegistry toolRegistry, Wire wire) {
        this.agent = agent;
        this.runtime = runtime;
        this.context = context;
        this.toolRegistry = toolRegistry;
        this.wire = wire;
        this.running = new AtomicBoolean(false);
        this.interrupted = new AtomicBoolean(false);
        
        // 创建执行器
        this.executor = new AgentExecutor(agent, runtime, context, toolRegistry, wire);
    }
    
    @Override
    public Mono<ExecutionResult> run(String input) {
        return run(input, null);
    }
    
    @Override
    public Mono<ExecutionResult> run(String input, String additionalContext) {
        return Mono.defer(() -> {
            if (running.getAndSet(true)) {
                return Mono.just(ExecutionResult.error("引擎正在运行中"));
            }
            
            interrupted.set(false);
            
            // 添加用户消息
            String fullInput = additionalContext != null
                    ? input + "\n\n" + additionalContext
                    : input;
            context.addMessage(Message.user(fullInput));
            
            log.info("开始执行: {}", truncate(input, 100));
            
            return executor.execute()
                    .doOnSuccess(result -> {
                        log.info("执行完成: steps={}, tokens={}", 
                                result.getStepsExecuted(), result.getTokensUsed());
                    })
                    .doOnError(e -> {
                        log.error("执行出错", e);
                    })
                    .doFinally(signal -> {
                        running.set(false);
                    });
        });
    }
    
    @Override
    public boolean isRunning() {
        return running.get();
    }
    
    @Override
    public void interrupt() {
        if (running.get()) {
            interrupted.set(true);
            executor.interrupt();
            log.info("引擎执行被中断");
        }
    }
    
    /**
     * 截断字符串用于日志
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
