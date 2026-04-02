package io.leavesfly.jimi.knowledge.memory;

import io.leavesfly.jimi.core.engine.ExecutionState;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import lombok.extern.slf4j.Slf4j;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆提取器
 * <p>
 * 对标 Claude Code 的 extractMemories（Per-turn capture）：
 * 每轮对话结束后，从 ExecutionState 和 Context 中提取有价值的信息，
 * 异步写入 MEMORY.md。
 * <p>
 * 提取策略（基于规则，不依赖 LLM 调用）：
 * <ul>
 *   <li>从 ExecutionState.filesModified 提取文件修改记录</li>
 *   <li>从 ExecutionState.keyDecisions 提取关键决策</li>
 *   <li>从 ExecutionState.lessonsLearned 提取经验教训</li>
 *   <li>从 Context 中提取用户查询的高层意图</li>
 * </ul>
 */
@Slf4j
public class MemoryExtractor {

    private final MemoryManager memoryManager;

    public MemoryExtractor(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /**
     * 从本轮执行中提取记忆并写入 MEMORY.md
     * <p>
     * 此方法应在 AgentExecutor.onExecutionSuccess() 中异步调用，不阻塞主流程。
     *
     * @param workDirPath    工作目录绝对路径
     * @param executionState 本轮执行状态
     * @param context        对话上下文
     * @param outcome        执行结果（"success" 或 "failed"）
     */
    public void extract(String workDirPath, ExecutionState executionState,
                        Context context, String outcome) {
        if (!memoryManager.getConfig().isAutoExtract()) {
            log.debug("Auto-extract is disabled, skipping memory extraction");
            return;
        }

        try {
            List<String> extractedEntries = new ArrayList<>();

            // 1. 提取关键决策
            extractKeyDecisions(executionState, extractedEntries);

            // 2. 提取经验教训
            extractLessonsLearned(executionState, extractedEntries);

            // 3. 提取文件修改摘要
            extractFileModifications(executionState, extractedEntries);

            if (extractedEntries.isEmpty()) {
                log.debug("No valuable memories extracted from this execution");
                return;
            }

            // 4. 写入 MEMORY.md
            String datePrefix = ZonedDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            for (String entry : extractedEntries) {
                String timestampedEntry = datePrefix + ": " + entry;
                memoryManager.appendMemory(workDirPath, detectSection(entry), timestampedEntry);
            }

            log.info("Extracted {} memory entries from execution (outcome={})",
                    extractedEntries.size(), outcome);

        } catch (Exception e) {
            log.warn("Failed to extract memories, skipping", e);
        }
    }

    /**
     * 提取关键决策
     */
    private void extractKeyDecisions(ExecutionState state, List<String> entries) {
        List<String> decisions = state.getKeyDecisions();
        if (decisions == null || decisions.isEmpty()) {
            return;
        }
        for (String decision : decisions) {
            if (isSignificant(decision)) {
                entries.add(decision);
                log.debug("Extracted key decision: {}", decision);
            }
        }
    }

    /**
     * 提取经验教训
     */
    private void extractLessonsLearned(ExecutionState state, List<String> entries) {
        List<String> lessons = state.getLessonsLearned();
        if (lessons == null || lessons.isEmpty()) {
            return;
        }
        for (String lesson : lessons) {
            if (isSignificant(lesson)) {
                entries.add(lesson);
                log.debug("Extracted lesson learned: {}", lesson);
            }
        }
    }

    /**
     * 提取文件修改摘要（仅当修改了多个文件时记录）
     */
    private void extractFileModifications(ExecutionState state, List<String> entries) {
        List<String> files = state.getFilesModified();
        if (files == null || files.size() < 2) {
            return;
        }

        String summary = "Modified " + files.size() + " files: "
                + files.stream()
                    .map(this::shortenPath)
                    .collect(Collectors.joining(", "));
        entries.add(summary);
    }

    /**
     * 从上下文中提取用户的高层意图
     */
    private String extractUserIntent(Context context) {
        List<Message> history = context.getHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg.getRole() == MessageRole.USER) {
                String text = msg.getContentParts().stream()
                        .filter(p -> p instanceof TextPart)
                        .map(p -> ((TextPart) p).getText())
                        .collect(Collectors.joining(" "));
                if (text.length() > 200) {
                    return text.substring(0, 200) + "...";
                }
                return text;
            }
        }
        return "";
    }

    /**
     * 判断条目是否有足够的信息量值得记忆
     */
    private boolean isSignificant(String entry) {
        return entry != null && entry.trim().length() > 10;
    }

    /**
     * 根据条目内容检测应写入的 section
     */
    private String detectSection(String entry) {
        String lower = entry.toLowerCase();
        if (lower.contains("决策") || lower.contains("decision") || lower.contains("选择") || lower.contains("chose")) {
            return "Key Decisions";
        }
        if (lower.contains("教训") || lower.contains("lesson") || lower.contains("注意") || lower.contains("avoid")) {
            return "Lessons Learned";
        }
        if (lower.contains("modified") || lower.contains("修改")) {
            return "Key Decisions";
        }
        return "Key Decisions";
    }

    /**
     * 缩短文件路径（只保留最后两级目录 + 文件名）
     */
    private String shortenPath(String fullPath) {
        String[] parts = fullPath.replace('\\', '/').split("/");
        if (parts.length <= 3) {
            return fullPath;
        }
        return ".../" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }
}
