package io.leavesfly.jimi.core.engine.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.llm.message.FunctionCall;
import io.leavesfly.jimi.llm.message.ToolCall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具调用过滤器
 * <p>
 * 职责：
 * - 过滤无效的工具调用
 * - 去重重复的工具调用
 * - 验证工具调用的完整性
 * - 确保 arguments 为有效 JSON 格式
 */
@Slf4j
@Component
public class ToolCallFilter {

    @Autowired
    private ObjectMapper objectMapper;

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

            // 标准化 arguments：确保为有效 JSON
            ToolCall normalizedTc = normalizeArguments(tc, i);
            if (normalizedTc == null) {
                continue;  // arguments 无法标准化，跳过
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
     * <p>
     * - 如果 arguments 为 null 或空字符串，设置为 "{}"
     * - 如果 arguments 不是有效 JSON，返回 null 表示跳过
     */
    private ToolCall normalizeArguments(ToolCall tc, int index) {
        String arguments = tc.getFunction().getArguments();

        // 处理 null 或空字符串
        if (arguments == null || arguments.trim().isEmpty()) {
            log.warn("工具调用#{} (id={}, name={}) 的 arguments 为空，设置为空 JSON 对象",
                    index, tc.getId(), tc.getFunction().getName());
            return rebuildWithArguments(tc, "{}");
        }

        // 验证 JSON 格式
        if (!isValidJson(arguments)) {
            log.error("工具调用#{} (id={}, name={}) 的 arguments 不是有效 JSON: {}，跳过此工具调用",
                    index, tc.getId(), tc.getFunction().getName(), arguments);
            return null;
        }

        return tc;
    }

    /**
     * 重建 ToolCall，使用新的 arguments
     */
    private ToolCall rebuildWithArguments(ToolCall tc, String newArguments) {
        return ToolCall.builder()
                .id(tc.getId())
                .type(tc.getType())
                .function(FunctionCall.builder()
                        .name(tc.getFunction().getName())
                        .arguments(newArguments)
                        .build())
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
}
