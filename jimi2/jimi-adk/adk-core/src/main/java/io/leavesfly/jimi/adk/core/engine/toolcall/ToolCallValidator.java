package io.leavesfly.jimi.adk.core.engine.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.adk.api.message.FunctionCall;
import io.leavesfly.jimi.adk.api.message.ToolCall;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具调用验证器
 * <p>
 * 职责：
 * - 验证工具调用的完整性和有效性
 * - 标准化工具调用ID
 * - 过滤无效的工具调用
 * - 去重重复的工具调用
 * - 确保 arguments 为有效 JSON 格式
 */
@Slf4j
public class ToolCallValidator {

    private final ObjectMapper objectMapper;

    public ToolCallValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 过滤有效的工具调用
     *
     * @param toolCalls 原始工具调用列表
     * @return 有效的工具调用列表
     */
    public List<ToolCall> filterValid(List<ToolCall> toolCalls) {
        List<ToolCall> validToolCalls = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall tc = toolCalls.get(i);

            if (!isValid(tc, i, seenIds)) {
                continue;
            }

            // 标准化 arguments
            ToolCall normalizedTc = normalizeArguments(tc, i);
            if (normalizedTc == null) {
                continue;
            }

            validToolCalls.add(normalizedTc);
            seenIds.add(normalizedTc.getId());
        }

        return validToolCalls;
    }

    /**
     * 验证工具调用是否有效
     */
    private boolean isValid(ToolCall tc, int index, Set<String> seenIds) {
        if (tc.getId() == null || tc.getId().trim().isEmpty()) {
            log.error("工具调用#{}缺少id，跳过此工具调用", index);
            return false;
        }

        if (seenIds.contains(tc.getId())) {
            log.error("发现重复的工具调用id: {}，跳过重复项", tc.getId());
            return false;
        }

        if (tc.getFunction() == null) {
            log.error("工具调用#{} (id={})缺少function对象，跳过此工具调用", index, tc.getId());
            return false;
        }

        if (tc.getFunction().getName() == null || tc.getFunction().getName().trim().isEmpty()) {
            log.error("工具调用#{} (id={})缺少function.name，跳过此工具调用", index, tc.getId());
            return false;
        }

        return true;
    }

    /**
     * 标准化 arguments，确保为有效 JSON
     */
    private ToolCall normalizeArguments(ToolCall tc, int index) {
        String arguments = tc.getFunction().getArguments();

        if (arguments == null || arguments.trim().isEmpty()) {
            log.warn("工具调用#{} (id={}, name={}) 的 arguments 为空，设置为空 JSON 对象",
                    index, tc.getId(), tc.getFunction().getName());
            return rebuildWithArguments(tc, "{}");
        }

        // 使用 ArgumentsNormalizer 标准化
        String normalized = ArgumentsNormalizer.normalizeToValidJson(arguments, objectMapper);

        if (!isValidJson(normalized)) {
            log.error("工具调用#{} (id={}, name={}) 的 arguments 标准化后仍不是有效 JSON: {}",
                    index, tc.getId(), tc.getFunction().getName(), normalized);
            return null;
        }

        if (!normalized.equals(arguments)) {
            return rebuildWithArguments(tc, normalized);
        }

        return tc;
    }

    /**
     * 重建 ToolCall，使用新的 arguments
     */
    private ToolCall rebuildWithArguments(ToolCall tc, String newArguments) {
        FunctionCall newFunction = FunctionCall.builder()
                .name(tc.getFunction().getName())
                .arguments(newArguments)
                .build();
        return ToolCall.builder()
                .id(tc.getId())
                .type(tc.getType())
                .function(newFunction)
                .build();
    }

    /**
     * 检查字符串是否为有效 JSON
     */
    private boolean isValidJson(String str) {
        try {
            objectMapper.readTree(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 验证单个工具调用
     *
     * @param toolCall 工具调用对象
     * @return 验证结果
     */
    public ValidationResult validate(ToolCall toolCall) {
        if (toolCall == null) {
            return ValidationResult.invalid("invalid_tool_call", "Error: Tool not found: null");
        }

        if (toolCall.getFunction() == null) {
            String id = toolCall.getId() != null ? toolCall.getId() : "unknown";
            return ValidationResult.invalid(id, "Error: Tool not found: null");
        }

        String toolName = toolCall.getFunction().getName();
        if (toolName == null || toolName.trim().isEmpty()) {
            String id = toolCall.getId() != null ? toolCall.getId() : "unknown";
            return ValidationResult.invalid(id, "Error: Tool not found: null");
        }

        String toolCallId = normalizeToolCallId(toolCall.getId(), toolName);
        return ValidationResult.valid(toolCallId);
    }

    /**
     * 标准化工具调用ID
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
