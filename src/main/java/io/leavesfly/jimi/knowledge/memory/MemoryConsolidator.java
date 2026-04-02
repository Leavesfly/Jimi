package io.leavesfly.jimi.knowledge.memory;

import io.leavesfly.jimi.config.info.MemoryConfig;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.message.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 记忆整理器（autoDream）
 * <p>
 * 对标 Claude Code 的 autoDream 后台整理机制：
 * <ul>
 *   <li>Phase 1 - Orient：评估当前记忆状态</li>
 *   <li>Phase 2 - Gather signal：从近期会话中收集有价值的信息</li>
 *   <li>Phase 3 - Consolidate：用 LLM 合并、去重、更新 MEMORY.md</li>
 *   <li>Phase 4 - Prune &amp; index：删除过期条目，保持精炼</li>
 * </ul>
 * <p>
 * 触发条件：会话数 >= consolidateMinSessions 且距上次整理 >= consolidateMinHours
 */
@Slf4j
public class MemoryConsolidator {

    private static final String LOCK_FILE = "consolidation.lock";
    private static final String LAST_CONSOLIDATION_FILE = ".last_consolidation";

    private static final String CONSOLIDATION_PROMPT = """
        You are a memory consolidation assistant. Your task is to organize and consolidate project memory.
        
        ## Current MEMORY.md Content
        
        %s
        
        ## Recent Session Insights
        
        %s
        
        ## Instructions
        
        Please consolidate the memory by:
        1. Merging duplicate or similar entries
        2. Removing outdated information
        3. Keeping the most important and recent insights
        4. Maintaining the existing section structure (Project Overview, User Preferences, Key Decisions, Lessons Learned)
        5. Ensuring the total content stays concise (under 2000 tokens)
        6. Preserving the Markdown format with ## section headers
        
        Output ONLY the consolidated MEMORY.md content, starting with "# Project Memory".
        Do not include any explanation or commentary.
        """;

    private final MemoryManager memoryManager;
    private final MemoryConfig config;

    /**
     * 整理锁（防止并发整理）
     * Key: workDirPath
     */
    private final ConcurrentMap<String, AtomicBoolean> consolidationLocks = new ConcurrentHashMap<>();

    public MemoryConsolidator(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
        this.config = memoryManager.getConfig();
    }

    /**
     * 检查是否应该触发整理，如果满足条件则执行
     *
     * @param workDirPath   工作目录绝对路径
     * @param sessionCount  当前会话数
     * @param llm           LLM 实例（用于智能整理）
     * @return 整理结果的 Mono
     */
    public Mono<Void> consolidateIfNeeded(String workDirPath, int sessionCount, LLM llm) {
        if (!config.isAutoConsolidate()) {
            return Mono.empty();
        }

        if (!shouldConsolidate(workDirPath, sessionCount)) {
            return Mono.empty();
        }

        return consolidate(workDirPath, llm);
    }

    /**
     * 执行记忆整理（四阶段）
     *
     * @param workDirPath 工作目录绝对路径
     * @param llm         LLM 实例
     * @return 完成的 Mono
     */
    public Mono<Void> consolidate(String workDirPath, LLM llm) {
        // 获取整理锁
        AtomicBoolean lock = consolidationLocks.computeIfAbsent(workDirPath, k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            log.debug("Consolidation already in progress for: {}", workDirPath);
            return Mono.empty();
        }

        log.info("Starting memory consolidation for: {}", workDirPath);

        return Mono.defer(() -> {
            try {
                // Phase 1 - Orient: 评估当前记忆状态
                String currentMemory = memoryManager.readMemory(workDirPath);
                if (currentMemory.isEmpty()) {
                    log.info("No memory to consolidate");
                    return Mono.empty();
                }

                // Phase 2 - Gather signal: 收集近期信息（从 MEMORY.md 本身）
                String recentInsights = gatherRecentInsights(currentMemory);

                // Phase 3 - Consolidate: 用 LLM 合并整理
                if (llm == null) {
                    log.info("No LLM available, performing rule-based consolidation");
                    performRuleBasedConsolidation(workDirPath, currentMemory);
                    return Mono.empty();
                }

                String prompt = String.format(CONSOLIDATION_PROMPT, currentMemory, recentInsights);

                return llm.getChatProvider()
                        .generate(
                                "You are a memory consolidation assistant.",
                                List.of(Message.user(prompt)),
                                Collections.emptyList())
                        .doOnNext(result -> {
                            String consolidated = result.getMessage().getTextContent();
                            if (consolidated != null && !consolidated.isEmpty()
                                    && consolidated.contains("# Project Memory")) {
                                // Phase 4 - Prune & index: 写入整理后的内容
                                memoryManager.overwriteMemory(workDirPath, consolidated.trim());
                                recordConsolidationTime(workDirPath);
                                log.info("Memory consolidation completed successfully");
                            } else {
                                log.warn("LLM consolidation returned invalid content, skipping");
                            }
                        })
                        .then();
            } catch (Exception e) {
                log.error("Memory consolidation failed", e);
                return Mono.empty();
            }
        }).doFinally(signal -> lock.set(false));
    }

    /**
     * 检查是否满足整理触发条件
     */
    private boolean shouldConsolidate(String workDirPath, int sessionCount) {
        // 条件 1: 会话数达到阈值
        if (sessionCount < config.getConsolidateMinSessions()) {
            return false;
        }

        // 条件 2: 距上次整理超过最小间隔
        Instant lastConsolidation = getLastConsolidationTime(workDirPath);
        if (lastConsolidation != null) {
            long hoursSinceLastConsolidation = ChronoUnit.HOURS.between(lastConsolidation, Instant.now());
            if (hoursSinceLastConsolidation < config.getConsolidateMinHours()) {
                return false;
            }
        }

        return true;
    }

    /**
     * 从 MEMORY.md 中收集近期的信息（用于整理参考）
     */
    private String gatherRecentInsights(String memoryContent) {
        // 提取带日期前缀的条目作为"近期信息"
        StringBuilder insights = new StringBuilder();
        String[] lines = memoryContent.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            // 匹配带日期前缀的条目（如 "- 2024-03-15: ..."）
            if (trimmed.startsWith("- ") && trimmed.length() > 14
                    && Character.isDigit(trimmed.charAt(2))) {
                insights.append(trimmed).append("\n");
            }
        }
        return insights.isEmpty() ? "No recent dated entries found." : insights.toString();
    }

    /**
     * 基于规则的简单整理（无 LLM 时的降级方案）
     */
    private void performRuleBasedConsolidation(String workDirPath, String currentMemory) {
        String[] lines = currentMemory.split("\n");
        StringBuilder consolidated = new StringBuilder();
        int entryCount = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            // 保留标题和非条目行
            if (trimmed.startsWith("#") || !trimmed.startsWith("- ")) {
                consolidated.append(line).append("\n");
                continue;
            }

            // 对条目行进行去重和限制数量
            entryCount++;
            if (entryCount <= config.getMaxMemoryTokens() / 10) {
                consolidated.append(line).append("\n");
            }
        }

        memoryManager.overwriteMemory(workDirPath, consolidated.toString().trim());
        recordConsolidationTime(workDirPath);
        log.info("Rule-based consolidation completed, kept {} entries", Math.min(entryCount, config.getMaxMemoryTokens() / 10));
    }

    /**
     * 获取上次整理时间
     */
    private Instant getLastConsolidationTime(String workDirPath) {
        MemoryStore store = memoryManager.getOrCreateStore(workDirPath);
        Path timestampFile = store.getMemoryRoot().resolve(LAST_CONSOLIDATION_FILE);
        if (!Files.isRegularFile(timestampFile)) {
            return null;
        }
        try {
            String timestamp = Files.readString(timestampFile).trim();
            return Instant.parse(timestamp);
        } catch (Exception e) {
            log.debug("Failed to read last consolidation time", e);
            return null;
        }
    }

    /**
     * 记录整理时间
     */
    private void recordConsolidationTime(String workDirPath) {
        MemoryStore store = memoryManager.getOrCreateStore(workDirPath);
        Path timestampFile = store.getMemoryRoot().resolve(LAST_CONSOLIDATION_FILE);
        try {
            Files.writeString(timestampFile, Instant.now().toString());
        } catch (IOException e) {
            log.warn("Failed to record consolidation time", e);
        }
    }
}
