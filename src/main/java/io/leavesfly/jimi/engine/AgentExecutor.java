package io.leavesfly.jimi.engine;

import io.leavesfly.jimi.agent.Agent;
import io.leavesfly.jimi.engine.context.Context;
import io.leavesfly.jimi.exception.MaxStepsReachedException;
import io.leavesfly.jimi.llm.ChatCompletionChunk;
import io.leavesfly.jimi.llm.ChatCompletionResult;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.llm.message.FunctionCall;
import io.leavesfly.jimi.engine.compaction.Compaction;
import io.leavesfly.jimi.engine.runtime.Runtime;
import io.leavesfly.jimi.engine.toolcall.ToolCallFilter;
import io.leavesfly.jimi.engine.toolcall.ToolCallValidator;
import io.leavesfly.jimi.engine.toolcall.ToolErrorTracker;
import io.leavesfly.jimi.skill.SkillMatcher;
import io.leavesfly.jimi.skill.SkillProvider;
import io.leavesfly.jimi.skill.SkillSpec;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.message.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行器
 * <p>
 * 职责：
 * - Agent 主循环调度
 * - LLM 交互处理
 * - 工具调用编排
 * - 步骤流程控制
 * <p>
 * 设计原则：
 * - 单一职责：专注于执行流程
 * - 无状态：所有状态由 Context 管理
 * - 可测试：纯业务逻辑，易于单元测试
 */
@Slf4j
public class AgentExecutor {

    private static final int MAX_THINKING_STEPS = 5; // 最大连续思考步数(无工具调用)

    private final Agent agent;
    private final Runtime runtime;
    private final Context context;
    private final Wire wire;
    private final ToolRegistry toolRegistry;
    private final Compaction compaction;
    private final boolean isSubagent;  // 标记是否为子Agent
    private final String agentName;    // Agent名称（用于显示）
    private final SkillMatcher skillMatcher;  // Skill匹配器（可选）
    private final SkillProvider skillProvider; // Skill提供者（可选）

    // 工具调用相关的辅助组件
    private final ToolCallValidator toolCallValidator = new ToolCallValidator();
    private final ToolCallFilter toolCallFilter = new ToolCallFilter();
    private final ToolErrorTracker toolErrorTracker = new ToolErrorTracker();

    // 用于跟踪连续的无工具调用步数
    private int consecutiveNoToolCallSteps = 0;

    /**
     * 主Agent构造函数（默认isSubagent=false）
     */
    public AgentExecutor(
            Agent agent,
            Runtime runtime,
            Context context,
            Wire wire,
            ToolRegistry toolRegistry,
            Compaction compaction
    ) {
        this(agent, runtime, context, wire, toolRegistry, compaction, false, null, null);
    }

    /**
     * 完整构造函数（支持子Agent标记和Skill组件）
     */
    public AgentExecutor(
            Agent agent,
            Runtime runtime,
            Context context,
            Wire wire,
            ToolRegistry toolRegistry,
            Compaction compaction,
            boolean isSubagent,
            SkillMatcher skillMatcher,
            SkillProvider skillProvider
    ) {
        this.agent = agent;
        this.runtime = runtime;
        this.context = context;
        this.wire = wire;
        this.toolRegistry = toolRegistry;
        this.compaction = compaction;
        this.isSubagent = isSubagent;
        this.agentName = agent.getName();
        this.skillMatcher = skillMatcher;
        this.skillProvider = skillProvider;
    }

    /**
     * 执行 Agent 任务
     *
     * @param userInput 用户输入
     * @return 执行完成的 Mono
     */
    public Mono<Void> execute(List<ContentPart> userInput) {
        return Mono.defer(() -> {
            // 创建用户消息
            Message userMessage = Message.user(userInput);
            
            // 估算用户输入的Token数（因为用户输入不会有LLM返回的usage）
            int userInputTokens = estimateTokensFromMessage(userMessage);
            int newTokenCount = context.getTokenCount() + userInputTokens;
            
            // 创建检查点 0，添加用户消息，并更新Token计数
            return context.checkpoint(false)
                    .then(context.appendMessage(userMessage))
                    .then(context.updateTokenCount(newTokenCount))
                    .doOnSuccess(v -> log.debug("Added user input: {} tokens (total: {})", 
                            userInputTokens, newTokenCount))
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
        // 获取并递增全局步数
        int globalStepNo = runtime.getSession().incrementAndGetGlobalStep();
        
        // 检查全局最大步数
        int maxSteps = runtime.getConfig().getLoopControl().getMaxStepsPerRun();
        if (globalStepNo > maxSteps) {
            return Mono.error(new MaxStepsReachedException(maxSteps));
        }

        // 发送步骤开始消息（使用全局步数）
        wire.send(new StepBegin(globalStepNo, isSubagent, agentName));
        
        log.debug("Agent '{}' local step {}, global step {}/{}", 
                agentName != null ? agentName : "main", stepNo, globalStepNo, maxSteps);

        return Mono.defer(() -> {
            // 检查上下文是否超限，触发压缩
            return checkAndCompactContext()
                    .then(context.checkpoint(false))
                    .then(matchAndInjectSkills(stepNo))  // 在每个步骤开始前匹配和注入 Skills
                    .then(step())
                    .flatMap(finished -> {
                        if (finished) {
                            log.info("Agent '{}' loop finished at local step {}, global step {}", 
                                    agentName != null ? agentName : "main", stepNo, globalStepNo);
                            return Mono.empty();
                        } else {
                            // 继续下一步
                            return agentLoopStep(stepNo + 1);
                        }
                    })
                    .onErrorResume(e -> {
                        log.error("Error in Agent '{}' at local step {}, global step {}", 
                                agentName != null ? agentName : "main", stepNo, globalStepNo, e);
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
            if (currentTokens > maxContextSize - EngineConstants.RESERVED_TOKENS) {
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
     * 匹配和注入 Skills（如果启用）
     *
     * @param stepNo 当前步骤号
     * @return 完成的 Mono
     */
    private Mono<Void> matchAndInjectSkills(int stepNo) {
        // 如果没有配置 Skill 组件，直接跳过
        if (skillMatcher == null || skillProvider == null) {
            return Mono.empty();
        }

        // 只在第一步执行 Skill 匹配（基于用户输入）
        // 后续步骤可以基于上下文进行动态匹配
        if (stepNo == 1) {
            return matchSkillsFromUserInput();
        }

        // TODO: 后续可以添加基于上下文的动态 Skill 匹配
        // return matchSkillsFromContext();

        return Mono.empty();
    }

    /**
     * 从用户输入匹配 Skills
     */
    private Mono<Void> matchSkillsFromUserInput() {
        return Mono.defer(() -> {
            // 获取最近的用户消息
            List<Message> history = context.getHistory();
            if (history.isEmpty()) {
                return Mono.empty();
            }

            // 从最后一条用户消息中提取内容
            Message lastMessage = history.get(history.size() - 1);
            if (lastMessage.getRole() != MessageRole.USER) {
                // 如果最后一条不是用户消息，向前查找
                for (int i = history.size() - 1; i >= 0; i--) {
                    if (history.get(i).getRole() == MessageRole.USER) {
                        lastMessage = history.get(i);
                        break;
                    }
                }
            }

            // 如果没找到用户消息，跳过
            if (lastMessage.getRole() != MessageRole.USER) {
                return Mono.empty();
            }

            // 提取内容部分
            List<ContentPart> contentParts = lastMessage.getContentParts();
            if (contentParts.isEmpty()) {
                return Mono.empty();
            }

            // 匹配 Skills
            List<SkillSpec> matchedSkills = skillMatcher.matchFromInput(contentParts);

            if (matchedSkills.isEmpty()) {
                log.debug("No skills matched from user input");
                return Mono.empty();
            }

            // 发送 Wire 消息
            wire.send(SkillsActivated.from(matchedSkills));

            // 注入 Skills
            return skillProvider.injectSkills(context, matchedSkills);
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
            List<Object> toolSchemas = new ArrayList<>(toolRegistry.getToolSchemas(agent.getTools()));

            // 记录调用前的Token数(用于计算本次调用的实际消耗)
            int tokensBefore = context.getTokenCount();
            
            return llm.getChatProvider()
                    .generateStream(agent.getSystemPrompt(), context.getHistory(), toolSchemas)
                    .reduce(new StreamAccumulator(), this::processStreamChunk)
                    .flatMap(this::handleStreamCompletion)
                    .onErrorResume(this::handleLLMError);
        });
    }

    /**
     * 处理流式数据块
     */
    private StreamAccumulator processStreamChunk(StreamAccumulator acc, ChatCompletionChunk chunk) {
        switch (chunk.getType()) {
            case CONTENT:
                handleContentChunk(acc, chunk);
                break;
            case TOOL_CALL:
                handleToolCallChunk(acc, chunk);
                break;
            case DONE:
                handleDoneChunk(acc, chunk);
                break;
        }
        return acc;
    }

    /**
     * 处理内容数据块
     */
    private void handleContentChunk(StreamAccumulator acc, ChatCompletionChunk chunk) {
        String contentDelta = chunk.getContentDelta();
        if (contentDelta != null && !contentDelta.isEmpty()) {
            acc.contentBuilder.append(contentDelta);
//            log.debug("Sending content delta to Wire: [{}]", contentDelta);
            // 根据是否为推理内容发送不同类型的消息
            ContentPartMessage.ContentType contentType = chunk.isReasoning() 
                    ? ContentPartMessage.ContentType.REASONING 
                    : ContentPartMessage.ContentType.NORMAL;
            wire.send(new ContentPartMessage(new TextPart(contentDelta), contentType));
        }
    }

    /**
     * 处理完成数据块
     */
    private void handleDoneChunk(StreamAccumulator acc, ChatCompletionChunk chunk) {
        log.debug("Stream completed, usage: {}", chunk.getUsage());
        acc.usage = chunk.getUsage();
    }

    /**
     * 处理流式完成后的逻辑
     */
    private Mono<Boolean> handleStreamCompletion(StreamAccumulator acc) {
        Message assistantMessage = buildMessageFromAccumulator(acc);
        
        // Token 计数更新：累加而不是替换
        Mono<Void> updateTokens;
        if (acc.usage != null) {
            // LLM返回了usage信息，累加到现有Token数
            int newTotalTokens = context.getTokenCount() + acc.usage.getTotalTokens();
            updateTokens = context.updateTokenCount(newTotalTokens);
            log.debug("Updated token count: {} + {} = {}", 
                    context.getTokenCount(), acc.usage.getTotalTokens(), newTotalTokens);
        } else {
            // LLM未返回usage信息，使用字符数估算(字符数/4)
            int estimatedTokens = estimateTokensFromMessage(assistantMessage);
            int newTotalTokens = context.getTokenCount() + estimatedTokens;
            updateTokens = context.updateTokenCount(newTotalTokens);
            log.debug("Estimated token count (no usage from LLM): {} + {} = {}", 
                    context.getTokenCount(), estimatedTokens, newTotalTokens);
        }

        return updateTokens
                .then(context.appendMessage(assistantMessage))
                .then(processAssistantMessage(assistantMessage));
    }

    /**
     * 处理LLM调用错误
     */
    private Mono<Boolean> handleLLMError(Throwable e) {
        if (e instanceof WebClientResponseException) {
            WebClientResponseException webEx = (WebClientResponseException) e;
            log.error("LLM API call failed: status={}, body={}",
                    webEx.getStatusCode(), webEx.getResponseBodyAsString(), e);
        } else {
            log.error("LLM API call failed", e);
        }
        return context.appendMessage(
                Message.assistant("抱歉，我遇到了一个错误：" + e.getMessage())
        ).thenReturn(true);
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
     * <p>
     * OpenAI流式API规范：
     * - 第一个chunk包含 id 和 function.name
     * - 后续chunk只包含 function.arguments 的增量（不包含id）
     * - 新的工具调用开始时才会有新的id
     * <p>
     * 容错处理：
     * - 某些LLM可能先发送arguments，后发送id和name
     * - 使用临时ID机制确保数据不丢失
     */
    private void handleToolCallChunk(StreamAccumulator acc, ChatCompletionChunk chunk) {
        // 防御性检查
        if (chunk == null) {
            log.warn("收到null的ToolCall chunk，忽略");
            return;
        }

        if (isNewToolCallStart(chunk)) {
            startNewToolCall(acc, chunk);
        }

        updateFunctionName(acc, chunk);
        appendArgumentsDelta(acc, chunk);
    }

    /**
     * 判断是否为新工具调用的开始
     */
    private boolean isNewToolCallStart(ChatCompletionChunk chunk) {
        return chunk.getToolCallId() != null && !chunk.getToolCallId().isEmpty();
    }

    /**
     * 开始新的工具调用
     */
    private void startNewToolCall(StreamAccumulator acc, ChatCompletionChunk chunk) {
        String newToolCallId = chunk.getToolCallId();

        // 检查是否是对临时ID的替换（临时ID以"temp_"开头）
        boolean isReplacingTempId = acc.currentToolCallId != null
                && acc.currentToolCallId.startsWith("temp_")
                && !newToolCallId.startsWith("temp_");

        if (isReplacingTempId) {
            // 用实际ID替换临时ID，保留已累积的arguments
            log.debug("用实际ID {} 替换临时ID {}", newToolCallId, acc.currentToolCallId);
            acc.currentToolCallId = newToolCallId;
            // 更新functionName（如果有）
            if (chunk.getFunctionName() != null) {
                acc.currentFunctionName = chunk.getFunctionName();
            }
        } else {
            // 这是一个全新的工具调用
            if (acc.currentToolCallId != null) {
                // 保存前一个工具调用
                acc.toolCalls.add(buildToolCall(acc));
            }

            // 初始化新的工具调用
            acc.currentToolCallId = newToolCallId;
            acc.currentFunctionName = chunk.getFunctionName();
            acc.currentArguments = new StringBuilder();
        }
    }

    /**
     * 更新函数名（处理函数名在后续chunk中才出现的情况）
     * <p>
     * 注意：允许覆盖临时上下文中的null函数名
     */
    private void updateFunctionName(StreamAccumulator acc, ChatCompletionChunk chunk) {
        String functionName = chunk.getFunctionName();
        if (functionName == null || functionName.isEmpty()) {
            return;
        }

        // 仅当当前有工具调用上下文，且函数名为空时才更新
        if (acc.currentToolCallId != null && acc.currentFunctionName == null) {
            acc.currentFunctionName = functionName;
            log.debug("更新toolCallId={} 的函数名: {}", acc.currentToolCallId, functionName);
        }
    }

    /**
     * 累积参数增量
     * <p>
     * 容错机制：当收到arguments但没有toolCallId时，创建临时上下文
     */
    private void appendArgumentsDelta(StreamAccumulator acc, ChatCompletionChunk chunk) {
        String argumentsDelta = chunk.getArgumentsDelta();
        if (argumentsDelta == null || argumentsDelta.isEmpty()) {
            return;
        }

        // 如果没有当前工具调用上下文，创建临时上下文
        if (acc.currentToolCallId == null) {
            initializeTempToolCallContext(acc, argumentsDelta);
        }

        // 累积参数
        acc.currentArguments.append(argumentsDelta);
    }

    /**
     * 初始化临时工具调用上下文
     * <p>
     * 某些LLM可能在第一个chunk就发送arguments，此时还没有toolCallId
     * 使用纳秒时间戳+线程ID确保唯一性
     */
    private void initializeTempToolCallContext(StreamAccumulator acc, String firstArgumentsDelta) {
        String tempId = "temp_" + System.nanoTime() + "_" + Thread.currentThread().getId();

        log.warn("收到argumentsDelta但currentToolCallId为null，创建临时上下文: id={}, argumentsDelta长度: {}",
                tempId, firstArgumentsDelta.length());
        log.debug("临时上下文的首个argumentsDelta: {}", firstArgumentsDelta);

        acc.currentToolCallId = tempId;
        acc.currentFunctionName = null;  // 等待后续chunk提供
        acc.currentArguments = new StringBuilder();
    }

    /**
     * 从累加器构建ToolCall
     */
    private ToolCall buildToolCall(StreamAccumulator acc) {
        // 验证必要字段
        if (acc.currentToolCallId == null) {
            log.error("构建ToolCall时缺少toolCallId");
        }
        if (acc.currentFunctionName == null) {
            log.error("构建ToolCall时缺少functionName, toolCallId: {}", acc.currentToolCallId);
        }

        String arguments = acc.currentArguments.toString();

        return ToolCall.builder()
                .id(acc.currentToolCallId)
                .type("function")
                .function(FunctionCall.builder()
                        .name(acc.currentFunctionName)
                        .arguments(arguments)
                        .build())
                .build();
    }

    /**
     * 从累加器构建完整的Message
     */
    private Message buildMessageFromAccumulator(StreamAccumulator acc) {

        finalizeCurrentToolCall(acc);

        String content = acc.contentBuilder.toString();
        int contentLength = content.length();
        int toolCallsCount = acc.toolCalls.size();

        // 添加调试日志，显示消息构建详情
        log.debug("构建Assistant消息: content_length={}, toolCalls_count={}", contentLength, toolCallsCount);
        if (log.isDebugEnabled() && contentLength > 0) {
            String contentPreview = contentLength > 100
                    ? content.substring(0, 100) + "... (截断)"
                    : content;
            log.debug("Assistant内容预览: {}", contentPreview);
        }

        List<ToolCall> validToolCalls = toolCallFilter.filterValid(acc.toolCalls);
        log.info("过滤后有效工具调用数量: {} (原始: {})", validToolCalls.size(), acc.toolCalls.size());

        return validToolCalls.isEmpty()
                ? Message.assistant(content)
                : Message.assistant(content.isEmpty() ? null : content, validToolCalls);
    }

    /**
     * 完成当前未完成的工具调用
     */
    private void finalizeCurrentToolCall(StreamAccumulator acc) {
        if (acc.currentToolCallId != null && !acc.currentToolCallId.isEmpty()) {
            acc.toolCalls.add(buildToolCall(acc));
            acc.currentToolCallId = null;
            acc.currentFunctionName = null;
            acc.currentArguments = new StringBuilder();
        }
    }

    /**
     * 处理assistant消息
     */
    private Mono<Boolean> processAssistantMessage(Message assistantMessage) {
        // 检查是否有工具调用
        if (assistantMessage.getToolCalls() == null || assistantMessage.getToolCalls().isEmpty()) {
            // 没有工具调用，增加计数器
            consecutiveNoToolCallSteps++;

            // 如果连续多步都只是思考没有行动,强制终止
            if (consecutiveNoToolCallSteps >= MAX_THINKING_STEPS) {
                log.warn("Agent has been thinking for {} consecutive steps without taking action, forcing completion",
                        consecutiveNoToolCallSteps);
                return Mono.just(true); // 强制结束
            }

            log.info("No tool calls, finishing step (consecutive thinking steps: {})", consecutiveNoToolCallSteps);
            return Mono.just(true);
        }

        // 有工具调用,重置计数器
        consecutiveNoToolCallSteps = 0;

        // 执行所有工具调用（简化日志，避免打印超长JSON）
        List<ToolCall> toolCalls = assistantMessage.getToolCalls();
        log.info("准备执行 {} 个工具调用", toolCalls.size());

        return executeToolCalls(toolCalls)
                .then(Mono.just(false)); // 继续循环
    }

    /**
     * 执行工具调用（串行执行）
     */
    private Mono<Void> executeToolCalls(List<ToolCall> toolCalls) {
        log.info("Starting sequential execution of {} tool calls", toolCalls.size());

        // 串行执行所有工具调用
        return Flux.fromIterable(toolCalls)
                .index() // 添加索引用于日志记录
                .concatMap(tuple -> {
                    long toolIndex = tuple.getT1();
                    ToolCall toolCall = tuple.getT2();
                    
                    log.info("Executing tool call #{}/{}", toolIndex + 1, toolCalls.size());
                    
                    return executeToolCall(toolCall)
                            .doOnError(e -> log.error("Tool call #{} failed", toolIndex, e))
                            .onErrorResume(e -> {
                                log.error("Caught error in tool call #{}, returning error message", toolIndex, e);
                                String toolCallId = (toolCall != null && toolCall.getId() != null)
                                        ? toolCall.getId() : "unknown_" + toolIndex;
                                return Mono.just(Message.tool(toolCallId,
                                        "Tool execution failed: " + e.getMessage()));
                            });
                })
                .collectList()
                .doOnNext(results -> log.info("Collected {} tool results after sequential execution", results.size()))
                .flatMap(results -> {
                    // 批量添加工具结果消息
                    return context.appendMessage(results)
                            .doOnSuccess(v -> log.info("Successfully appended {} tool results to context", results.size()))
                            .doOnError(e -> log.error("Failed to append tool results to context", e));
                });
    }

    /**
     * 执行单个工具调用
     */
    private Mono<Message> executeToolCall(ToolCall toolCall) {
        return Mono.defer(() -> {
            try {
                ToolCallValidator.ValidationResult validation = toolCallValidator.validate(toolCall);
                if (!validation.isValid()) {
                    log.error("Tool call validation failed: {}", validation.getErrorMessage());
                    return Mono.just(Message.tool(validation.getToolCallId(), validation.getErrorMessage()));
                }

                String toolName = toolCall.getFunction().getName();
                String toolCallId = validation.getToolCallId();
                String rawArgs = toolCall.getFunction().getArguments();
                String toolSignature = toolName + ":" + rawArgs;

                return executeValidToolCall(toolName, rawArgs, toolCallId, toolSignature);
            } catch (Exception e) {
                log.error("Unexpected error in executeToolCall", e);
                String errorToolCallId = (toolCall != null && toolCall.getId() != null)
                        ? toolCall.getId() : "unknown";
                return Mono.just(Message.tool(errorToolCallId,
                        "Internal error executing tool: " + e.getMessage()));
            }
        });
    }

    /**
     * 执行有效的工具调用
     */
    private Mono<Message> executeValidToolCall(String toolName, String arguments,
                                               String toolCallId, String toolSignature) {
        return toolRegistry.execute(toolName, arguments)
                .map(result -> convertToolResultToMessage(result, toolCallId, toolSignature))
                .onErrorResume(e -> {
                    log.error("Tool execution failed: {}", toolName, e);
                    return Mono.just(Message.tool(toolCallId, "Tool execution error: " + e.getMessage()));
                });
    }

    /**
     * 将工具结果转换为消息
     */
    private Message convertToolResultToMessage(ToolResult result, String toolCallId, String toolSignature) {
        String content;

        if (result.isOk()) {
            toolErrorTracker.clearErrors();
            content = formatToolResult(result);
        } else if (result.isError()) {
            toolErrorTracker.trackError(toolSignature);
            content = toolErrorTracker.buildErrorContent(result.getMessage(), result.getOutput(), toolSignature);
        } else {
            content = result.getMessage();
        }

        return Message.tool(toolCallId, content);
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

    /**
     * 从消息估算Token数量（回退机制）
     * 当LLM未返回usage信息时，使用字符数除以4的方式估算
     * 
     * @param message 要估算的消息
     * @return 估算的Token数量
     */
    private int estimateTokensFromMessage(Message message) {
        int charCount = 0;
        
        // 估算内容部分的字符数 - 使用getTextContent()可以处理String和List<ContentPart>两种情况
        String textContent = message.getTextContent();
        if (textContent != null && !textContent.isEmpty()) {
            charCount += textContent.length();
        }
        
        // 估算工具调用的字符数
        if (message.getToolCalls() != null) {
            for (ToolCall toolCall : message.getToolCalls()) {
                if (toolCall.getId() != null) {
                    charCount += toolCall.getId().length();
                }
                if (toolCall.getFunction() != null) {
                    FunctionCall function = toolCall.getFunction();
                    if (function.getName() != null) {
                        charCount += function.getName().length();
                    }
                    if (function.getArguments() != null) {
                        charCount += function.getArguments().length();
                    }
                }
            }
        }
        
        // 使用字符数除以4作为Token数估算（通用经验值）
        int estimatedTokens = (int) Math.ceil(charCount / 4.0);
        log.debug("Estimated {} tokens from {} characters", estimatedTokens, charCount);
        return estimatedTokens;
    }
}
