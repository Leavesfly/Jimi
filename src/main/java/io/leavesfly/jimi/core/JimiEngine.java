package io.leavesfly.jimi.core;

import io.leavesfly.jimi.core.agent.Agent;
import io.leavesfly.jimi.core.engine.AgentExecutor;
import io.leavesfly.jimi.core.engine.EngineConstants;
import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.exception.LLMNotSetException;

import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.tool.ToolRegistry;

import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.WireAware;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JimiEngine - Engine 的核心实现
 * <p>
 * 职责：
 * - 作为 Engine 接口的实现
 * - 协调各组件（AgentExecutor、Context等）
 * - 提供统一的对外API
 * - 管理组件生命周期
 * <p>
 * 设计原则：
 * - Facade 模式：简化为 AgentExecutor 的门面
 * - 委托执行：将所有操作委托给 AgentExecutor
 * - 轻量协调：仅负责初始化和组件装配
 */
@Slf4j
public class JimiEngine implements Engine {

    // ==================== 内部组件 ====================
    private final AgentExecutor executor;

    /**
     * 私有构造函数
     *
     * @param executor AgentExecutor 实例
     */
    private JimiEngine(AgentExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor is required");

        // 设置 Approval 事件转发（仅主 Agent 订阅）
        if (!executor.isSubagent()) {
            executor.getRuntime().getApproval().asFlux().subscribe(executor.getWire()::send);
        }

        // 为所有实现 WireAware 接口的工具注入 Wire
        executor.getToolRegistry().getAllTools().forEach(tool -> {
            if (tool instanceof WireAware) {
                ((WireAware) tool).setWire(executor.getWire());
            }
        });
    }

    /**
     * 静态工厂方法，创建 JimiEngine 实例
     *
     * @param executor AgentExecutor 实例
     * @return JimiEngine 实例
     */
    public static JimiEngine create(AgentExecutor executor) {
        return new JimiEngine(executor);
    }

    // ==================== Getter 方法 ====================

    public Agent getAgent() {
        return executor.getAgent();
    }

    public JimiRuntime getRuntime() {
        return executor.getRuntime();
    }

    public Context getContext() {
        return executor.getContext();
    }

    public ToolRegistry getToolRegistry() {
        return executor.getToolRegistry();
    }

    public Wire getWire() {
        return executor.getWire();
    }

    // ==================== Engine 接口实现 ====================

    @Override
    public String getName() {
        return executor.getAgent().getName();
    }

    @Override
    public String getModel() {
        LLM llm = executor.getRuntime().getLlm();
        return llm != null ? llm.getModelName() : "unknown";
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        Context context = executor.getContext();
        JimiRuntime jimiRuntime = executor.getRuntime();
        
        status.put("messageCount", context.getHistory().size());
        status.put("tokenCount", context.getTokenCount());
        status.put("checkpointCount", context.getnCheckpoints());
        
        LLM llm = jimiRuntime.getLlm();
        if (llm != null) {
            int maxContextSize = llm.getMaxContextSize();
            int used = context.getTokenCount();
            int available = Math.max(0, maxContextSize - EngineConstants.RESERVED_TOKENS - used);
            double usagePercent = maxContextSize > 0 ? (used * 100.0 / maxContextSize) : 0.0;
            status.put("maxContextSize", maxContextSize);
            status.put("reservedTokens", EngineConstants.RESERVED_TOKENS);
            status.put("availableTokens", available);
            status.put("contextUsagePercent", Math.round(usagePercent * 100.0) / 100.0);
        }
        return status;
    }

    @Override
    public Mono<Void> run(String userInput) {
        return run(List.of(TextPart.of(userInput)));
    }

    @Override
    public Mono<Void> run(List<ContentPart> userInput) {
        return run(userInput, false);
    }

    @Override
    public Mono<Void> run(String userInput, boolean skipKnowledge) {
        return run(List.of(TextPart.of(userInput)), skipKnowledge);
    }

    @Override
    public Mono<Void> run(List<ContentPart> userInput, boolean skipKnowledge) {
        return Mono.defer(() -> {
            if (executor.getRuntime().getLlm() == null) {
                return Mono.error(new LLMNotSetException());
            }
            return executor.execute(userInput, skipKnowledge);
        });
    }
}