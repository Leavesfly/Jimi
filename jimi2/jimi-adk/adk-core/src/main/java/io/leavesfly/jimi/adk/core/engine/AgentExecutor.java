package io.leavesfly.jimi.adk.core.engine;

import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.llm.ChatCompletionChunk;
import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.api.message.Role;
import io.leavesfly.jimi.adk.api.message.TextPart;
import io.leavesfly.jimi.adk.api.message.ToolCall;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.core.engine.toolcall.ArgumentsNormalizer;
import io.leavesfly.jimi.adk.core.engine.toolcall.ToolCallValidator;
import io.leavesfly.jimi.adk.core.engine.toolcall.ToolErrorTracker;
import io.leavesfly.jimi.adk.core.wire.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Agent 执行器
 * 负责 Agent 主循环的执行逻辑
 */
@Slf4j
public class AgentExecutor {
    
    /**
     * 最大执行步数
     */
    private static final int DEFAULT_MAX_STEPS = 100;
    
    /**
     * 最大连续思考步数（无工具调用）
     */
    private static final int MAX_THINKING_STEPS = 5;
    
    private final Agent agent;
    private final Runtime runtime;
    private final Context context;
    private final ToolRegistry toolRegistry;
    private final Wire wire;
    
    /**
     * 工具调用验证器
     */
    private final ToolCallValidator toolCallValidator;
    
    /**
     * 工具错误追踪器
     */
    private final ToolErrorTracker toolErrorTracker;
    
    /**
     * ObjectMapper 用于参数标准化
     */
    private final ObjectMapper objectMapper;
    
    /**
     * 执行状态管理器
     */
    @Getter
    private final ExecutionState executionState;
    
    /**
     * 是否被中断
     */
    private final AtomicBoolean interrupted;
    
    public AgentExecutor(Agent agent, Runtime runtime, Context context,
                        ToolRegistry toolRegistry, Wire wire) {
        this.agent = agent;
        this.runtime = runtime;
        this.context = context;
        this.toolRegistry = toolRegistry;
        this.wire = wire;
        this.objectMapper = new ObjectMapper();
        this.toolCallValidator = new ToolCallValidator(objectMapper);
        this.toolErrorTracker = new ToolErrorTracker();
        this.executionState = new ExecutionState();
        this.interrupted = new AtomicBoolean(false);
    }
    
    /**
     * 执行 Agent 主循环
     */
    public Mono<ExecutionResult> execute() {
        executionState.initializeTask(null);
        interrupted.set(false);
        toolErrorTracker.clearErrors();
        
        // 创建初始检查点
        context.createCheckpoint(0);
        
        return executeLoop()
                .onErrorResume(e -> {
                    log.error("执行出错", e);
                    return Mono.just(ExecutionResult.error(e.getMessage()));
                });
    }
    
    /**
     * 执行循环
     */
    private Mono<ExecutionResult> executeLoop() {
        return Mono.defer(() -> {
            int step = executionState.incrementStep();
            
            // 检查中断
            if (interrupted.get()) {
                wire.send(new StepInterrupted());
                return Mono.just(ExecutionResult.interrupted());
            }
            
            // 检查步数限制
            if (step > DEFAULT_MAX_STEPS) {
                wire.send(StatusUpdate.warning("超出最大步数限制: " + DEFAULT_MAX_STEPS));
                return Mono.just(ExecutionResult.error("超出最大步数限制"));
            }
            
            // 检查连续思考步数
            if (executionState.getConsecutiveNoToolCallSteps() >= MAX_THINKING_STEPS) {
                return Mono.just(ExecutionResult.success(
                        "Agent 连续思考 " + MAX_THINKING_STEPS + " 步未调用工具，强制结束",
                        step - 1, executionState.getTokensInTask()));
            }
            
            // 检查工具错误追踪器是否要求终止
            if (toolErrorTracker.shouldTerminateLoop()) {
                return Mono.just(ExecutionResult.error(
                        "检测到连续重复的工具调用错误，终止执行"));
            }
            
            // 发送步骤开始消息
            wire.send(new StepBegin(step));
            
            // 创建检查点
            context.createCheckpoint(step);
            
            return executeStep(step);
        });
    }
    
    /**
     * 执行单个步骤
     */
    private Mono<ExecutionResult> executeStep(int step) {
        // 获取工具 Schema（从工具实例中提取名称）
        List<String> toolNames = agent.getTools() != null 
                ? agent.getTools().stream().map(Tool::getName).collect(Collectors.toList())
                : Collections.emptyList();
        List<Object> toolSchemas = new ArrayList<>(toolRegistry.getToolSchemas(toolNames));
        
        // 调用 LLM
        return runtime.getLlm().generateStream(
                        agent.getSystemPrompt(),
                        context.getHistory(),
                        toolSchemas
                )
                .reduce(new StreamAccumulator(), this::processChunk)
                .flatMap(acc -> handleResponse(step, acc));
    }
    
    /**
     * 处理流式响应块
     */
    private StreamAccumulator processChunk(StreamAccumulator acc, ChatCompletionChunk chunk) {
        if (chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
            // 记录 Token 使用
            if (chunk.getUsage() != null) {
                acc.usage = chunk.getUsage();
            }
            return acc;
        }
        
        ChatCompletionChunk.Choice choice = chunk.getChoices().get(0);
        ChatCompletionChunk.Delta delta = choice.getDelta();
        
        if (delta == null) {
            return acc;
        }
        
        // 处理文本内容
        if (delta.getContent() != null) {
            acc.contentBuilder.append(delta.getContent());
            // 发送内容消息
            wire.send(new ContentPartMessage(TextPart.of(delta.getContent())));
        }
        
        // 处理推理内容
        if (delta.getReasoningContent() != null) {
            acc.reasoningBuilder.append(delta.getReasoningContent());
            wire.send(new ContentPartMessage(
                    TextPart.of(delta.getReasoningContent()),
                    ContentPartMessage.ContentType.REASONING));
        }
        
        // 处理工具调用
        if (delta.getToolCalls() != null) {
            for (ToolCall tc : delta.getToolCalls()) {
                if (tc.getId() != null || (tc.getFunction() != null && tc.getFunction().getName() != null)) {
                    // 新的工具调用
                    acc.toolCalls.add(tc);
                } else if (tc.getFunction() != null && tc.getFunction().getArguments() != null) {
                    // 追加参数到最后一个工具调用
                    if (!acc.toolCalls.isEmpty()) {
                        ToolCall lastTc = acc.toolCalls.get(acc.toolCalls.size() - 1);
                        if (lastTc.getFunction() != null) {
                            String currentArgs = lastTc.getFunction().getArguments();
                            lastTc.getFunction().setArguments(
                                    (currentArgs == null ? "" : currentArgs) + tc.getFunction().getArguments()
                            );
                        }
                    }
                }
            }
        }
        
        return acc;
    }
    
    /**
     * 处理响应
     */
    private Mono<ExecutionResult> handleResponse(int step, StreamAccumulator acc) {
        // 更新 Token 计数
        if (acc.usage != null) {
            int total = acc.usage.getTotalTokens();
            int input = acc.usage.getPromptTokens();
            int output = acc.usage.getCompletionTokens();
            executionState.addTokens(total, input, output);
            
            // 发送 Token 使用量消息
            wire.send(new TokenUsageMessage(input, output, total, executionState.getTokensInTask()));
        }
        
        String content = acc.contentBuilder.toString();
        boolean hasToolCalls = !acc.toolCalls.isEmpty();
        
        // 构建 Assistant 消息
        Message assistantMessage = Message.builder()
                .role(Role.ASSISTANT)
                .content(content.isEmpty() ? null : content)
                .toolCalls(hasToolCalls ? acc.toolCalls : null)
                .build();
        context.addMessage(assistantMessage);
        
        // 发送步骤结束消息
        wire.send(new StepEnd(step, hasToolCalls));
        
        if (hasToolCalls) {
            // 重置思考步数
            executionState.resetNoToolCallCounter();
            // 执行工具
            return executeTools(acc.toolCalls)
                    .then(executeLoop());
        } else {
            // 增加思考步数
            boolean shouldStop = executionState.shouldForceComplete(MAX_THINKING_STEPS);
            if (!shouldStop) {
                // 可以继续执行
                return executeLoop();
            }
            // 完成执行
            return Mono.just(ExecutionResult.success(content, step, executionState.getTokensInTask()));
        }
    }
    
    /**
     * 执行工具调用
     */
    private Mono<Void> executeTools(List<ToolCall> toolCalls) {
        // 使用验证器过滤有效的工具调用
        List<ToolCall> validToolCalls = toolCallValidator.filterValid(toolCalls);
        
        if (validToolCalls.isEmpty()) {
            log.warn("所有工具调用均无效，跳过执行");
            return Mono.empty();
        }
        
        return Flux.fromIterable(validToolCalls)
                .flatMap(tc -> {
                    String toolName = tc.getFunction().getName();
                    String arguments = tc.getFunction().getArguments();
                    
                    // 使用 ArgumentsNormalizer 标准化参数
                    String normalizedArgs = ArgumentsNormalizer.normalizeToValidJson(arguments, objectMapper);
                    
                    // 发送工具调用消息
                    wire.send(new ToolCallMessage(tc));
                    
                    return toolRegistry.execute(toolName, normalizedArgs)
                            .doOnNext(result -> {
                                // 记录工具使用
                                executionState.recordToolUsed(toolName);
                                
                                // 追踪错误
                                if (!result.isOk()) {
                                    String toolSignature = toolName + ":" + normalizedArgs.hashCode();
                                    toolErrorTracker.trackError(toolSignature);
                                }
                                
                                // 发送工具结果消息
                                wire.send(new ToolResultMessage(tc.getId(), toolName, result));
                                
                                // 添加工具结果到上下文
                                context.addMessage(Message.toolResult(
                                        tc.getId(),
                                        result.isOk() ? result.getMessage() : result.getError()
                                ));
                            });
                })
                .then();
    }
    
    /**
     * 中断执行
     */
    public void interrupt() {
        interrupted.set(true);
    }
    
    /**
     * 流式响应累积器
     */
    private static class StreamAccumulator {
        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        ChatCompletionChunk.Usage usage;
    }
}
