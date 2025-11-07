package io.leavesfly.jimi.soul;

import io.leavesfly.jimi.agent.Agent;
import io.leavesfly.jimi.exception.MaxStepsReachedException;
import io.leavesfly.jimi.llm.ChatCompletionChunk;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.llm.message.FunctionCall;
import io.leavesfly.jimi.soul.compaction.Compaction;
import io.leavesfly.jimi.soul.context.Context;
import io.leavesfly.jimi.soul.runtime.Runtime;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.message.*;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行器
 * 
 * 职责：
 * - Agent 主循环调度
 * - LLM 交互处理
 * - 工具调用编排
 * - 步骤流程控制
 * 
 * 设计原则：
 * - 单一职责：专注于执行流程
 * - 无状态：所有状态由 Context 管理
 * - 可测试：纯业务逻辑，易于单元测试
 */
@Slf4j
public class AgentExecutor {
    
    private static final int RESERVED_TOKENS = 50_000;
    
    private final Agent agent;
    private final Runtime runtime;
    private final Context context;
    private final Wire wire;
    private final ToolRegistry toolRegistry;
    private final Compaction compaction;
    
    public AgentExecutor(
            Agent agent,
            Runtime runtime,
            Context context,
            Wire wire,
            ToolRegistry toolRegistry,
            Compaction compaction
    ) {
        this.agent = agent;
        this.runtime = runtime;
        this.context = context;
        this.wire = wire;
        this.toolRegistry = toolRegistry;
        this.compaction = compaction;
    }
    
    /**
     * 执行 Agent 任务
     * 
     * @param userInput 用户输入
     * @return 执行完成的 Mono
     */
    public Mono<Void> execute(List<ContentPart> userInput) {
        return Mono.defer(() -> {
            // 创建检查点 0
            return context.checkpoint(false)
                    .flatMap(checkpointId -> context.appendMessage(Message.user(userInput)))
                    .then(agentLoop())
                    .doOnSuccess(v -> log.info("Agent execution completed"))
                    .doOnError(e -> log.error("Agent execution failed", e));
        });
    }
    
    /**
     * Agent 主循环
     */
    private Mono<Void> agentLoop() {
        return Mono.defer(() -> agentLoopStep(1));
    }
    
    /**
     * Agent 循环步骤
     */
    private Mono<Void> agentLoopStep(int stepNo) {
        // 检查最大步数
        int maxSteps = runtime.getConfig().getLoopControl().getMaxStepsPerRun();
        if (stepNo > maxSteps) {
            return Mono.error(new MaxStepsReachedException(maxSteps));
        }
        
        // 发送步骤开始消息
        wire.send(new StepBegin(stepNo));
        
        return Mono.defer(() -> {
            // 检查上下文是否超限，触发压缩
            return checkAndCompactContext()
                    .then(context.checkpoint(true))
                    .then(step())
                    .flatMap(finished -> {
                        if (finished) {
                            log.info("Agent loop finished at step {}", stepNo);
                            return Mono.empty();
                        } else {
                            // 继续下一步
                            return agentLoopStep(stepNo + 1);
                        }
                    })
                    .onErrorResume(e -> {
                        log.error("Error in step {}", stepNo, e);
                        wire.send(new StepInterrupted());
                        return Mono.error(e);
                    });
        });
    }
    
    /**
     * 检查并压缩上下文（如果需要）
     */
    private Mono<Void> checkAndCompactContext() {
        return Mono.defer(() -> {
            LLM llm = runtime.getLlm();
            if (llm == null) {
                return Mono.empty();
            }
            
            int currentTokens = context.getTokenCount();
            int maxContextSize = llm.getMaxContextSize();
            
            // 检查是否需要压缩（Token 数超过限制 - 预留Token）
            if (currentTokens > maxContextSize - RESERVED_TOKENS) {
                log.info("Context size ({} tokens) approaching limit ({} tokens), triggering compaction",
                        currentTokens, maxContextSize);
                
                // 发送压缩开始事件
                wire.send(new CompactionBegin());
                
                return compaction.compact(context.getHistory(), llm)
                        .flatMap(compactedMessages -> {
                            // 回退到检查点 0（保留系统提示词和初始检查点）
                            return context.revertTo(0)
                                    .then(Mono.defer(() -> {
                                        // 添加压缩后的消息
                                        return context.appendMessage(compactedMessages);
                                    }))
                                    .doOnSuccess(v -> {
                                        log.info("Context compacted successfully");
                                        wire.send(new CompactionEnd());
                                    })
                                    .doOnError(e -> {
                                        log.error("Context compaction failed", e);
                                        wire.send(new CompactionEnd());
                                    });
                        });
            }
            
            return Mono.empty();
        });
    }
    
    /**
     * 执行单步
     *
     * @return 是否完成（true 表示没有更多工具调用）
     */
    private Mono<Boolean> step() {
        return Mono.defer(() -> {
            LLM llm = runtime.getLlm();
            
            // 生成工具 Schema
            List<Object> toolSchemas = new ArrayList<>(toolRegistry.getToolSchemas(agent.getTools()));
            
            // 使用流式API调用 LLM
            return llm.getChatProvider()
                    .generateStream(
                            agent.getSystemPrompt(),
                            context.getHistory(),
                            toolSchemas
                    )
                    .reduce(new StreamAccumulator(), (acc, chunk) -> {
                        // 处理流式数据块
                        if (chunk.getType() == ChatCompletionChunk.ChunkType.CONTENT && chunk.getContentDelta() != null) {
                            // 累积文本内容
                            acc.contentBuilder.append(chunk.getContentDelta());
                            // 发送到Wire以实时显示
                            log.debug("Sending content delta to Wire: [{}]", chunk.getContentDelta());
                            wire.send(new ContentPartMessage(new TextPart(chunk.getContentDelta())));
                        } else if (chunk.getType() == ChatCompletionChunk.ChunkType.TOOL_CALL) {
                            // 处理工具调用
                            handleToolCallChunk(acc, chunk);
                        } else if (chunk.getType() == ChatCompletionChunk.ChunkType.DONE) {
                            // 保存使用统计
                            log.debug("Stream completed, usage: {}", chunk.getUsage());
                            acc.usage = chunk.getUsage();
                        }
                        return acc;
                    })
                    .flatMap(acc -> {
                        // 构建完整的assistant消息
                        Message assistantMessage = buildMessageFromAccumulator(acc);
                        
                        // 更新token计数
                        Mono<Void> updateTokens = Mono.empty();
                        if (acc.usage != null) {
                            updateTokens = context.updateTokenCount(acc.usage.getTotalTokens());
                        }
                        
                        // 添加消息到上下文并处理
                        return updateTokens
                                .then(context.appendMessage(assistantMessage))
                                .then(processAssistantMessage(assistantMessage));
                    })
                    .onErrorResume(e -> {
                        log.error("LLM API call failed", e);
                        return context.appendMessage(
                                Message.assistant("抱歉，我遇到了一个错误：" + e.getMessage())
                        ).thenReturn(true);
                    });
        });
    }
    
    /**
     * 流式累加器
     */
    private static class StreamAccumulator {
        StringBuilder contentBuilder = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        ChatCompletionResult.Usage usage;
        
        // 用于临时存储正在构建的工具调用
        String currentToolCallId;
        String currentFunctionName;
        StringBuilder currentArguments = new StringBuilder();
    }
    
    /**
     * 处理工具调用数据块
     */
    private void handleToolCallChunk(StreamAccumulator acc, ChatCompletionChunk chunk) {
        if (chunk.getToolCallId() != null) {
            // 新的工具调用开始
            if (acc.currentToolCallId != null) {
                // 保存上一个工具调用
                acc.toolCalls.add(buildToolCall(acc));
            }
            acc.currentToolCallId = chunk.getToolCallId();
            acc.currentFunctionName = chunk.getFunctionName();
            acc.currentArguments = new StringBuilder();
        }
        
        // 累积参数
        if (chunk.getArgumentsDelta() != null) {
            acc.currentArguments.append(chunk.getArgumentsDelta());
        }
    }
    
    /**
     * 从累加器构建ToolCall
     */
    private ToolCall buildToolCall(StreamAccumulator acc) {
        return ToolCall.builder()
                .id(acc.currentToolCallId)
                .type("function")
                .function(FunctionCall.builder()
                        .name(acc.currentFunctionName)
                        .arguments(acc.currentArguments.toString())
                        .build())
                .build();
    }
    
    /**
     * 从累加器构建Message
     */
    private Message buildMessageFromAccumulator(StreamAccumulator acc) {
        // 如果还有未完成的工具调用，添加它
        if (acc.currentToolCallId != null) {
            acc.toolCalls.add(buildToolCall(acc));
        }
        
        String content = acc.contentBuilder.toString();
        if (!acc.toolCalls.isEmpty()) {
            return Message.assistant(content.isEmpty() ? null : content, acc.toolCalls);
        } else {
            return Message.assistant(content);
        }
    }
    
    /**
     * 处理assistant消息
     */
    private Mono<Boolean> processAssistantMessage(Message assistantMessage) {
        // 检查是否有工具调用
        if (assistantMessage.getToolCalls() == null || assistantMessage.getToolCalls().isEmpty()) {
            // 没有工具调用，结束循环
            log.info("No tool calls, finishing step");
            return Mono.just(true);
        }
        
        // 执行所有工具调用
        return executeToolCalls(assistantMessage.getToolCalls())
                .then(Mono.just(false)); // 继续循环
    }
    
    /**
     * 执行工具调用
     */
    private Mono<Void> executeToolCalls(List<ToolCall> toolCalls) {
        // 并行执行所有工具调用
        List<Mono<Message>> toolResultMonos = new ArrayList<>();
        
        for (ToolCall toolCall : toolCalls) {
            Mono<Message> resultMono = executeToolCall(toolCall);
            toolResultMonos.add(resultMono);
        }
        
        // 等待所有工具执行完成，并添加结果到上下文
        return Flux.merge(toolResultMonos)
                .collectList()
                .flatMap(results -> {
                    // 批量添加工具结果消息
                    return context.appendMessage(results);
                });
    }
    
    /**
     * 执行单个工具调用
     */
    private Mono<Message> executeToolCall(ToolCall toolCall) {
        String toolName = toolCall.getFunction().getName();
        String arguments = toolCall.getFunction().getArguments();
        String toolCallId = toolCall.getId();
        
        log.info("Executing tool: {} with id: {}", toolName, toolCallId);
        
        return toolRegistry.execute(toolName, arguments)
                .map(result -> {
                    // 将工具结果转换为消息
                    String content;
                    if (result.isOk()) {
                        content = formatToolResult(result);
                    } else if (result.isError()) {
                        content = "Error: " + result.getMessage();
                        if (!result.getOutput().isEmpty()) {
                            content += "\n" + result.getOutput();
                        }
                    } else {
                        // REJECTED
                        content = result.getMessage();
                    }
                    
                    return Message.tool(content, toolCallId);
                })
                .onErrorResume(e -> {
                    log.error("Tool execution failed: {}", toolName, e);
                    return Mono.just(Message.tool(
                            "Tool execution error: " + e.getMessage(),
                            toolCallId
                    ));
                });
    }
    
    /**
     * 格式化工具结果
     */
    private String formatToolResult(ToolResult result) {
        StringBuilder sb = new StringBuilder();
        
        if (!result.getOutput().isEmpty()) {
            sb.append(result.getOutput());
        }
        
        if (!result.getMessage().isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(result.getMessage());
        }
        
        return sb.toString();
    }
}
