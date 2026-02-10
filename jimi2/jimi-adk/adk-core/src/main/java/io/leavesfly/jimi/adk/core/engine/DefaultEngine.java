package io.leavesfly.jimi.adk.core.engine;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.core.engine.compaction.Compaction;
import io.leavesfly.jimi.adk.core.wire.messages.CompactionBegin;
import io.leavesfly.jimi.adk.core.wire.messages.CompactionEnd;
import io.leavesfly.jimi.adk.core.wire.messages.StatusUpdate;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认引擎实现
 * 协调 Agent 执行、工具调用和上下文管理
 */
@Slf4j
@Getter
public class DefaultEngine implements Engine {
    
    /**
     * 预留 Token 数（用于系统提示和新消息）
     */
    private static final int RESERVED_TOKENS = 8000;
    
    /**
     * 最小消息数阈值（少于此数不触发压缩）
     */
    private static final int MIN_MESSAGES_FOR_COMPACTION = 10;
    
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
     * 上下文压缩器（可选）
     */
    private final Compaction compaction;
    
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
                        ToolRegistry toolRegistry, Wire wire, Compaction compaction) {
        this.agent = agent;
        this.runtime = runtime;
        this.context = context;
        this.toolRegistry = toolRegistry;
        this.wire = wire;
        this.compaction = compaction;
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
            
            // 上下文压缩检查
            Mono<Void> compactionStep = Mono.empty();
            if (shouldCompact()) {
                compactionStep = performCompaction();
            }
            
            return compactionStep.then(executor.execute())
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
     * 检查是否需要进行上下文压缩
     */
    private boolean shouldCompact() {
        if (compaction == null) {
            return false;
        }
        
        int messageCount = context.getHistory().size();
        if (messageCount < MIN_MESSAGES_FOR_COMPACTION) {
            return false;
        }
        
        // 基于 Token 数量检查
        int currentTokens = context.getTokenCount();
        int maxContextTokens = runtime.getConfig().getMaxContextTokens();
        
        if (currentTokens > 0 && maxContextTokens > 0) {
            // Token 数接近上限时触发压缩
            return currentTokens > maxContextTokens - RESERVED_TOKENS;
        }
        
        // 回退到基于消息数的检查（Token 计数不可用时）
        return messageCount > 20;
    }
    
    /**
     * 执行上下文压缩
     */
    private Mono<Void> performCompaction() {
        int beforeCount = context.getHistory().size();
        
        // 发送压缩开始事件
        wire.send(new CompactionBegin());
        wire.send(StatusUpdate.info("正在压缩上下文 (" + beforeCount + " 条消息)..."));
        
        return compaction.compact(context.getHistory(), runtime.getLlm())
                .doOnNext(compactedMessages -> {
                    int afterCount = compactedMessages.size();
                    log.info("上下文压缩完成: {} -> {} 条消息", beforeCount, afterCount);
                    context.replaceHistory(compactedMessages);
                    wire.send(new CompactionEnd(beforeCount, afterCount, true));
                })
                .doOnError(e -> {
                    log.warn("上下文压缩失败，使用原始历史", e);
                    wire.send(new CompactionEnd(beforeCount, beforeCount, false));
                })
                .onErrorResume(e -> Mono.just(context.getHistory()))
                .then();
    }
    
    /**
     * 截断字符串用于日志
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
