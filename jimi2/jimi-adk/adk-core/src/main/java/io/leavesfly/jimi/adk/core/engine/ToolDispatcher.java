package io.leavesfly.jimi.adk.core.engine;

import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.interaction.Approval;
import io.leavesfly.jimi.adk.api.interaction.ApprovalResponse;
import io.leavesfly.jimi.adk.api.message.Message;
import io.leavesfly.jimi.adk.api.message.ToolCall;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.core.engine.toolcall.ArgumentsNormalizer;
import io.leavesfly.jimi.adk.core.engine.toolcall.ToolCallValidator;
import io.leavesfly.jimi.adk.core.engine.toolcall.ToolErrorTracker;
import io.leavesfly.jimi.adk.core.wire.messages.ToolCallMessage;
import io.leavesfly.jimi.adk.core.wire.messages.ToolResultMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * 工具调度器
 * <p>
 * 职责：
 * - 验证和过滤工具调用
 * - 标准化工具参数
 * - 审批检查（当工具需要审批且提供了 Approval 时）
 * - 执行工具（直接调用 Tool，不经过 ToolRegistry.execute()）
 * - 追踪工具错误
 * - 将工具结果添加到上下文
 * </p>
 */
@Slf4j
public class ToolDispatcher {

    private final ToolRegistry toolRegistry;
    private final Context context;
    private final Wire wire;
    private final ObjectMapper objectMapper;
    private final ToolCallValidator toolCallValidator;
    private final ToolErrorTracker toolErrorTracker;
    private final TaskMetrics taskMetrics;

    /** 审批服务（可选） */
    private Approval approval;

    public ToolDispatcher(ToolRegistry toolRegistry, Context context, Wire wire,
                          TaskMetrics taskMetrics) {
        this.toolRegistry = toolRegistry;
        this.context = context;
        this.wire = wire;
        this.objectMapper = new ObjectMapper();
        this.toolCallValidator = new ToolCallValidator(objectMapper);
        this.toolErrorTracker = new ToolErrorTracker();
        this.taskMetrics = taskMetrics;
    }

    /**
     * 设置审批服务（可选）
     */
    public void setApproval(Approval approval) {
        this.approval = approval;
    }

    /**
     * 执行工具调用列表
     *
     * @param toolCalls 工具调用列表
     * @return 完成信号
     */
    public Mono<Void> dispatch(List<ToolCall> toolCalls) {
        // 使用验证器过滤有效的工具调用
        List<ToolCall> validToolCalls = toolCallValidator.filterValid(toolCalls);

        if (validToolCalls.isEmpty()) {
            log.warn("所有工具调用均无效，跳过执行");
            return Mono.empty();
        }

        return Flux.fromIterable(validToolCalls)
                .flatMap(this::executeSingle)
                .then();
    }

    /**
     * 执行单个工具调用
     * <p>
     * 完整执行流：查找 → 标准化参数 → 反序列化 → 审批 → 执行 → 追踪
     * </p>
     */
    private Mono<Void> executeSingle(ToolCall tc) {
        String toolName = tc.getFunction().getName();
        String arguments = tc.getFunction().getArguments();

        // 1. 查找工具
        Optional<Tool<?>> toolOpt = toolRegistry.getTool(toolName);
        if (toolOpt.isEmpty()) {
            log.warn("工具不存在: {}", toolName);
            ToolResult errorResult = ToolResult.error("工具不存在: " + toolName);
            wire.send(new ToolResultMessage(tc.getId(), toolName, errorResult));
            context.addMessage(Message.toolResult(tc.getId(), errorResult.getError()));
            return Mono.empty();
        }

        Tool<?> tool = toolOpt.get();

        // 2. 标准化参数
        String normalizedArgs = ArgumentsNormalizer.normalizeToValidJson(arguments, objectMapper);

        // 发送工具调用消息
        wire.send(new ToolCallMessage(tc));

        // 3. 反序列化参数
        Object params;
        try {
            params = objectMapper.readValue(
                    normalizedArgs == null || normalizedArgs.isEmpty() ? "{}" : normalizedArgs,
                    tool.getParamsType()
            );
        } catch (JsonProcessingException e) {
            log.error("工具参数解析失败: {} 错误: {}", toolName, e.getMessage());
            ToolResult errorResult = ToolResult.error("参数解析失败: " + e.getMessage());
            wire.send(new ToolResultMessage(tc.getId(), toolName, errorResult));
            context.addMessage(Message.toolResult(tc.getId(), errorResult.getError()));
            return Mono.empty();
        }

        // 4. 审批检查
        if (tool.requiresApproval() && approval != null && !approval.isYolo()) {
            String approvalDesc = getApprovalDescription(tool, params);
            ApprovalResponse response = approval.requestApproval(toolName, toolName, approvalDesc);
            if (response == ApprovalResponse.REJECT) {
                log.info("工具调用被用户拒绝: {}", toolName);
                ToolResult rejected = ToolResult.error("操作被用户拒绝: " + toolName);
                wire.send(new ToolResultMessage(tc.getId(), toolName, rejected));
                context.addMessage(Message.toolResult(tc.getId(), rejected.getError()));
                return Mono.empty();
            }
        }

        // 5. 执行工具
        return executeTool(tool, params)
                .doOnNext(result -> {
                    // 记录工具使用
                    taskMetrics.recordToolUsed(toolName);

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
                })
                .then();
    }

    /**
     * 执行工具（类型安全调用）
     */
    @SuppressWarnings("unchecked")
    private <P> Mono<ToolResult> executeTool(Tool<?> tool, Object params) {
        try {
            Tool<P> typedTool = (Tool<P>) tool;
            P typedParams = (P) params;
            return typedTool.execute(typedParams);
        } catch (Exception e) {
            log.error("工具执行失败: {}", tool.getName(), e);
            return Mono.just(ToolResult.error("执行失败: " + e.getMessage()));
        }
    }

    /**
     * 获取审批描述（类型安全）
     */
    @SuppressWarnings("unchecked")
    private <P> String getApprovalDescription(Tool<?> tool, Object params) {
        Tool<P> typedTool = (Tool<P>) tool;
        P typedParams = (P) params;
        return typedTool.getApprovalDescription(typedParams);
    }

    /**
     * 检查工具错误追踪器是否要求终止循环
     *
     * @return true 如果应终止
     */
    public boolean shouldTerminateLoop() {
        return toolErrorTracker.shouldTerminateLoop();
    }

    /**
     * 清除工具错误记录
     */
    public void clearErrors() {
        toolErrorTracker.clearErrors();
    }
}
