package io.leavesfly.jimi.ui.shell.handler;

import io.leavesfly.jimi.config.info.ShellUIConfig;
import io.leavesfly.jimi.core.interaction.HumanInputRequest;
import io.leavesfly.jimi.core.interaction.approval.ApprovalRequest;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.ui.shell.output.SpinnerManager;
import io.leavesfly.jimi.ui.shell.output.ToolVisualization;
import io.leavesfly.jimi.ui.shell.output.AssistantTextRenderer;
import io.leavesfly.jimi.ui.shell.output.OutputFormatter;
import io.leavesfly.jimi.wire.message.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Wire 消息处理器
 * 负责处理所有 Wire 消息的分发和处理
 */
@Slf4j
public class WireMessageHandler {

    private final OutputFormatter outputFormatter;
    private final SpinnerManager spinnerManager;
    private final AssistantTextRenderer renderer;
    private final InteractionHandler interactionHandler;
    private final ToolVisualization toolVisualization;
    private final ShellUIConfig uiConfig;
    private final AtomicReference<String> currentStatus;
    private final Map<String, String> activeTools;

    // Token 使用回调
    private Consumer<TokenUsageMessage> tokenUsageCallback;
    // 快捷提示回调
    private Consumer<String> shortcutsHintCallback;

    public WireMessageHandler(
            OutputFormatter outputFormatter,
            SpinnerManager spinnerManager,
            AssistantTextRenderer renderer,
            InteractionHandler interactionHandler,
            ToolVisualization toolVisualization,
            ShellUIConfig uiConfig,
            AtomicReference<String> currentStatus) {
        this.outputFormatter = outputFormatter;
        this.spinnerManager = spinnerManager;
        this.renderer = renderer;
        this.interactionHandler = interactionHandler;
        this.toolVisualization = toolVisualization;
        this.uiConfig = uiConfig;
        this.currentStatus = currentStatus;
        this.activeTools = new ConcurrentHashMap<>();
    }

    /**
     * 设置 Token 使用回调
     */
    public void setTokenUsageCallback(Consumer<TokenUsageMessage> callback) {
        this.tokenUsageCallback = callback;
    }

    /**
     * 设置快捷提示回调
     */
    public void setShortcutsHintCallback(Consumer<String> callback) {
        this.shortcutsHintCallback = callback;
    }

    /**
     * 处理 Wire 消息，根据消息类型分发到对应的处理方法
     */
    public void handle(WireMessage message) {
        try {
            if (message instanceof StepBegin stepBegin) {
                handleStepBegin(stepBegin);
            } else if (message instanceof StepInterrupted) {
                handleStepInterrupted();
            } else if (message instanceof CompactionBegin) {
                handleCompactionBegin();
            } else if (message instanceof CompactionEnd) {
                handleCompactionEnd();
            } else if (message instanceof StatusUpdate statusUpdate) {
                handleStatusUpdate(statusUpdate);
            } else if (message instanceof ContentPartMessage contentMsg) {
                handleContentPart(contentMsg);
            } else if (message instanceof ToolCallMessage toolCallMsg) {
                handleToolCall(toolCallMsg);
            } else if (message instanceof ToolResultMessage toolResultMsg) {
                handleToolResult(toolResultMsg);
            } else if (message instanceof TokenUsageMessage tokenUsageMsg) {
                if (tokenUsageCallback != null) {
                    tokenUsageCallback.accept(tokenUsageMsg);
                }
            } else if (message instanceof ApprovalRequest approvalRequest) {
                log.info("[WireMessageHandler] Received ApprovalRequest: action={}, description={}",
                        approvalRequest.getAction(), approvalRequest.getDescription());
                interactionHandler.handleApprovalRequest(approvalRequest);
            } else if (message instanceof HumanInputRequest humanInputRequest) {
                log.info("[WireMessageHandler] Received HumanInputRequest: type={}, question={}",
                        humanInputRequest.getInputType(), truncateForLog(humanInputRequest.getQuestion()));
                interactionHandler.handleHumanInputRequest(humanInputRequest);
            } else {
                log.debug("Unhandled wire message type: {}", message.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.error("Error handling wire message: {}", message.getClass().getSimpleName(), e);
        }
    }

    private void handleStepBegin(StepBegin stepBegin) {
        if (stepBegin.isSubagent()) {
            String agentName = stepBegin.getAgentName() != null ? stepBegin.getAgentName() : "subagent";
            outputFormatter.printStatus("  🤖 [" + agentName + "] Step " + stepBegin.getStepNumber() + " - Thinking...");
        } else {
            currentStatus.set("thinking (step " + stepBegin.getStepNumber() + ")");
            outputFormatter.printStatus("🧠 Step " + stepBegin.getStepNumber() + " - Thinking...");

            if (uiConfig.isShowSpinner()) {
                spinnerManager.start("正在思考...");
            }
            if (stepBegin.getStepNumber() == 1 && shortcutsHintCallback != null) {
                shortcutsHintCallback.accept("thinking");
            }

            renderer.resetForNewStep();
        }
    }

    private void handleStepInterrupted() {
        currentStatus.set("interrupted");
        activeTools.clear();
        renderer.flushLineIfNeeded();
        outputFormatter.printError("⚠️  Step interrupted");
        if (shortcutsHintCallback != null) {
            shortcutsHintCallback.accept("error");
        }
    }

    private void handleCompactionBegin() {
        currentStatus.set("compacting");
        outputFormatter.printStatus("🗜️  Compacting context...");
    }

    private void handleCompactionEnd() {
        currentStatus.set("ready");
        outputFormatter.printSuccess("✅ Context compacted");
    }

    private void handleStatusUpdate(StatusUpdate statusUpdate) {
        Map<String, Object> statusMap = statusUpdate.getStatus();
        String status = statusMap.getOrDefault("status", "unknown").toString();
        currentStatus.set(status);
    }

    private void handleContentPart(ContentPartMessage contentMsg) {
        if (uiConfig.isShowSpinner()) {
            spinnerManager.stop();
        }

        ContentPart part = contentMsg.getContentPart();
        if (part instanceof TextPart textPart) {
            boolean isReasoning = contentMsg.getContentType() == ContentPartMessage.ContentType.REASONING;
            renderer.print(textPart.getText(), isReasoning);
        }
    }

    private void handleToolCall(ToolCallMessage toolCallMsg) {
        if (uiConfig.isShowSpinner()) {
            spinnerManager.stop();
        }

        renderer.flushLineIfNeeded();

        ToolCall toolCall = toolCallMsg.getToolCall();
        String toolName = toolCall.getFunction().getName();
        activeTools.put(toolCall.getId(), toolName);

        String displayMode = uiConfig.getToolDisplayMode();
        switch (displayMode) {
            case "minimal" -> outputFormatter.printStatus("🔧 " + toolName);
            case "compact" -> {
                String args = toolCall.getFunction().getArguments();
                int truncateLen = uiConfig.getToolArgsTruncateLength();
                if (args != null && args.length() > truncateLen) {
                    args = args.substring(0, truncateLen) + "...";
                }
                outputFormatter.printStatus("🔧 " + toolName + " | " + (args != null ? args : ""));
            }
            default -> toolVisualization.onToolCallStart(toolCall);
        }
    }

    private void handleToolResult(ToolResultMessage toolResultMsg) {
        String toolCallId = toolResultMsg.getToolCallId();
        ToolResult result = toolResultMsg.getToolResult();

        String displayMode = uiConfig.getToolDisplayMode();
        switch (displayMode) {
            case "minimal" -> {
                if (result.isOk()) {
                    outputFormatter.printSuccess("✅ " + activeTools.get(toolCallId));
                } else {
                    outputFormatter.printError("❌ " + activeTools.get(toolCallId));
                }
            }
            case "compact" -> {
                String resultPreview = result.isOk() ? "✅ 成功" : "❌ 失败: " + result.getMessage();
                outputFormatter.printInfo("  → " + resultPreview);
            }
            default -> toolVisualization.onToolCallComplete(toolCallId, result);
        }

        activeTools.remove(toolCallId);
    }

    /**
     * 清理活动工具
     */
    public void clearActiveTools() {
        activeTools.clear();
    }

    /**
     * 截断字符串用于日志输出
     */
    private String truncateForLog(String text) {
        if (text == null) return null;
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
}
