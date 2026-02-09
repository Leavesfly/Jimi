package io.leavesfly.jimi.adk.core.engine.toolcall;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具错误追踪器
 * <p>
 * 职责：
 * - 追踪工具调用错误
 * - 检测重复错误
 * - 提供错误统计和警告
 * - 在连续失败超过阈值时标记应终止循环
 */
@Slf4j
public class ToolErrorTracker {

    private static final int MAX_REPEATED_ERRORS = 3;

    private final List<String> recentToolErrors = new ArrayList<>();
    private boolean shouldTerminate = false;

    /**
     * 记录工具错误
     *
     * @param toolSignature 工具签名（工具名:参数摘要）
     */
    public void trackError(String toolSignature) {
        recentToolErrors.add(toolSignature);
        if (recentToolErrors.size() > MAX_REPEATED_ERRORS) {
            recentToolErrors.remove(0);
        }

        if (isRepeatedError(toolSignature)) {
            shouldTerminate = true;
            log.error("检测到连续 {} 次相同的工具调用错误: {}，标记应终止循环",
                    MAX_REPEATED_ERRORS, toolSignature);
        }
    }

    /**
     * 清除错误记录
     */
    public void clearErrors() {
        recentToolErrors.clear();
        shouldTerminate = false;
    }

    /**
     * 检查是否应终止循环
     */
    public boolean shouldTerminateLoop() {
        return shouldTerminate;
    }

    /**
     * 检查是否为重复错误
     */
    public boolean isRepeatedError(String toolSignature) {
        return recentToolErrors.stream().allMatch(sig -> sig.equals(toolSignature))
                && recentToolErrors.size() >= MAX_REPEATED_ERRORS;
    }

    /**
     * 构建不完整JSON错误消息
     */
    public String buildIncompleteJsonErrorMessage(String normalizedArgs, String toolSignature) {
        String errorMsg = "Error: Incomplete tool call arguments received from LLM. Arguments: " + normalizedArgs;

        if (isRepeatedError(toolSignature)) {
            errorMsg += "\n\nCRITICAL: This same incomplete JSON error has occurred " +
                    MAX_REPEATED_ERRORS + " times. The LLM appears to be stuck. " +
                    "Please report this issue or try a different model.";
            log.error("Detected repeated incomplete JSON errors: {}", toolSignature);
        }

        return errorMsg;
    }

    /**
     * 构建工具执行错误内容
     */
    public String buildErrorContent(String errorMessage, String output, String toolSignature) {
        StringBuilder content = new StringBuilder("Error: ").append(errorMessage);

        if (output != null && !output.isEmpty()) {
            content.append("\n").append(output);
        }

        if (isRepeatedError(toolSignature)) {
            content.append("\n\nWARNING: You have called this tool with the same arguments ")
                    .append(MAX_REPEATED_ERRORS)
                    .append(" times and it keeps failing. ")
                    .append("Please try a different approach or different arguments.");
            log.warn("Detected repeated tool call errors: {}", toolSignature);
        }

        return content.toString();
    }
}
