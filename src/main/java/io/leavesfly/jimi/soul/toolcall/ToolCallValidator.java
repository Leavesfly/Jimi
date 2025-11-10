package io.leavesfly.jimi.soul.toolcall;

import io.leavesfly.jimi.llm.message.ToolCall;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具调用验证器
 * <p>
 * 职责：
 * - 验证工具调用的完整性和有效性
 * - 标准化工具调用ID
 * - 提供验证结果封装
 */
@Slf4j
public class ToolCallValidator {

    /**
     * 验证工具调用
     *
     * @param toolCall 工具调用对象
     * @return 验证结果
     */
    public ValidationResult validate(ToolCall toolCall) {
        if (toolCall == null) {
            log.error("Received null toolCall");
            return ValidationResult.invalid("invalid_tool_call", "Error: Tool not found: null");
        }

        if (toolCall.getFunction() == null) {
            log.error("ToolCall {} has null function", toolCall.getId());
            String id = toolCall.getId() != null ? toolCall.getId() : "unknown";
            return ValidationResult.invalid(id, "Error: Tool not found: null");
        }

        String toolName = toolCall.getFunction().getName();
        if (toolName == null || toolName.trim().isEmpty()) {
            log.error("ToolCall {} has null or empty tool name. ToolCall: {}", toolCall.getId(), toolCall);
            String id = toolCall.getId() != null ? toolCall.getId() : "unknown";
            return ValidationResult.invalid(id, "Error: Tool not found: null");
        }

        String toolCallId = normalizeToolCallId(toolCall.getId(), toolName);
        return ValidationResult.valid(toolCallId);
    }

    /**
     * 标准化工具调用ID
     *
     * @param rawToolCallId 原始工具调用ID
     * @param toolName      工具名称
     * @return 标准化后的工具调用ID
     */
    private String normalizeToolCallId(String rawToolCallId, String toolName) {
        if (rawToolCallId == null || rawToolCallId.trim().isEmpty()) {
            log.warn("ToolCall for {} has no ID, generating one", toolName);
            return "generated_" + System.currentTimeMillis();
        }
        return rawToolCallId;
    }

    /**
     * 工具调用验证结果
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String toolCallId;
        private final String errorMessage;

        private ValidationResult(boolean valid, String toolCallId, String errorMessage) {
            this.valid = valid;
            this.toolCallId = toolCallId;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult valid(String toolCallId) {
            return new ValidationResult(true, toolCallId, null);
        }

        public static ValidationResult invalid(String toolCallId, String errorMessage) {
            return new ValidationResult(false, toolCallId, errorMessage);
        }

        public boolean isValid() {
            return valid;
        }

        public String getToolCallId() {
            return toolCallId;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
