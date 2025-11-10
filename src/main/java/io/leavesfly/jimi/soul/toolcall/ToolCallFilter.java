package io.leavesfly.jimi.soul.toolcall;

import io.leavesfly.jimi.llm.message.ToolCall;
import lombok.extern.slf4j.Slf4j;

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
 */
@Slf4j
public class ToolCallFilter {

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

            validToolCalls.add(tc);
            seenIds.add(tc.getId());
            logValidToolCall(tc, validToolCalls.size() - 1);
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

        if (tc.getFunction().getArguments() == null) {
            log.error("工具调用#{} (id={}, name={})的arguments为null，将使用空对象",
                    index, tc.getId(), tc.getFunction().getName());
        }

        return true;
    }

    /**
     * 记录有效工具调用信息
     */
    private void logValidToolCall(ToolCall tc, int index) {
        log.info("有效工具调用#{}: id={}, function={}, arguments_length={}",
                index,
                tc.getId(),
                tc.getFunction().getName(),
                tc.getFunction().getArguments() != null ? tc.getFunction().getArguments().length() : 0);
    }
}
