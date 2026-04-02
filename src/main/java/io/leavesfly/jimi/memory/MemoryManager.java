package io.leavesfly.jimi.memory;

import io.leavesfly.jimi.config.info.MemoryConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆系统管理器
 * <p>
 * 对标 Claude Code 的记忆系统架构，提供三层记忆管理：
 * <ul>
 *   <li>Layer 1 - MEMORY.md：索引层，始终注入 System Prompt</li>
 *   <li>Layer 2 - Topic files：主题文件层，按需加载（Phase 4）</li>
 *   <li>Layer 3 - Session transcripts：会话记录层，仅 grep 搜索（已有 .jsonl）</li>
 * </ul>
 */
@Slf4j
public class MemoryManager {

    private final MemoryConfig config;

    /**
     * MemoryStore 缓存（按工作目录路径缓存）
     */
    private final Map<String, MemoryStore> storeCache = new ConcurrentHashMap<>();

    public MemoryManager(MemoryConfig config) {
        this.config = config;
    }

    /**
     * 获取指定工作目录的记忆摘要（用于注入 System Prompt）
     * <p>
     * 对应 Claude Code 的 Layer 1：MEMORY.md 始终注入到上下文中
     *
     * @param workDirPath 工作目录绝对路径
     * @return 记忆摘要内容，如果记忆系统未启用或无内容则返回空字符串
     */
    public String getMemorySummary(String workDirPath) {
        if (!config.isEnabled()) {
            return "";
        }

        MemoryStore store = getOrCreateStore(workDirPath);
        store.initializeIfAbsent(workDirPath);

        String memoryContent = store.readMemoryMd();
        if (memoryContent.isEmpty()) {
            return "";
        }

        return formatForSystemPrompt(memoryContent);
    }

    /**
     * 手动写入记忆（对应 Claude Code 的 Manual write）
     *
     * @param workDirPath 工作目录绝对路径
     * @param section     记忆区域（如 "User Preferences"、"Key Decisions"）
     * @param content     要写入的内容
     */
    public void writeMemory(String workDirPath, String section, String content) {
        if (!config.isEnabled()) {
            log.debug("Memory system is disabled, skipping write");
            return;
        }

        MemoryStore store = getOrCreateStore(workDirPath);
        String existing = store.readMemoryMd();

        String updated = updateSection(existing, section, content);
        store.writeMemoryMd(updated);
        log.info("Memory updated: section={}", section);
    }

    /**
     * 追加记忆条目到指定区域
     *
     * @param workDirPath 工作目录绝对路径
     * @param section     记忆区域
     * @param entry       要追加的条目
     */
    public void appendMemory(String workDirPath, String section, String entry) {
        if (!config.isEnabled()) {
            return;
        }

        MemoryStore store = getOrCreateStore(workDirPath);
        String existing = store.readMemoryMd();

        String updated = appendToSection(existing, section, entry);
        store.writeMemoryMd(updated);
        log.debug("Memory appended: section={}, entry={}", section, entry);
    }

    /**
     * 读取完整的 MEMORY.md 内容
     *
     * @param workDirPath 工作目录绝对路径
     * @return MEMORY.md 原始内容
     */
    public String readMemory(String workDirPath) {
        if (!config.isEnabled()) {
            return "";
        }
        return getOrCreateStore(workDirPath).readMemoryMd();
    }

    /**
     * 覆盖写入完整的 MEMORY.md 内容
     *
     * @param workDirPath 工作目录绝对路径
     * @param content     完整内容
     */
    public void overwriteMemory(String workDirPath, String content) {
        if (!config.isEnabled()) {
            return;
        }
        getOrCreateStore(workDirPath).writeMemoryMd(content);
    }

    /**
     * 获取 MemoryStore 实例（带缓存）
     */
    public MemoryStore getOrCreateStore(String workDirPath) {
        return storeCache.computeIfAbsent(workDirPath,
                path -> new MemoryStore(path, config.getStoragePath()));
    }

    /**
     * 获取配置
     */
    public MemoryConfig getConfig() {
        return config;
    }

    /**
     * 格式化记忆内容用于 System Prompt 注入
     */
    private String formatForSystemPrompt(String memoryContent) {
        return "## Long-term Memory\n\n"
                + "The following is your persistent memory about this project and user preferences. "
                + "Use this information to provide more contextual and personalized assistance.\n\n"
                + memoryContent;
    }

    /**
     * 更新 MEMORY.md 中指定 section 的内容
     */
    private String updateSection(String markdown, String sectionName, String newContent) {
        String sectionHeader = "## " + sectionName;
        int sectionStart = markdown.indexOf(sectionHeader);

        if (sectionStart == -1) {
            // section 不存在，追加到末尾
            return markdown + "\n\n" + sectionHeader + "\n" + newContent + "\n";
        }

        // 找到下一个 ## 标题的位置
        int contentStart = sectionStart + sectionHeader.length();
        int nextSection = markdown.indexOf("\n## ", contentStart);

        if (nextSection == -1) {
            // 这是最后一个 section
            return markdown.substring(0, contentStart) + "\n" + newContent + "\n";
        } else {
            return markdown.substring(0, contentStart) + "\n" + newContent + "\n"
                    + markdown.substring(nextSection);
        }
    }

    /**
     * 向 MEMORY.md 中指定 section 追加条目
     */
    private String appendToSection(String markdown, String sectionName, String entry) {
        String sectionHeader = "## " + sectionName;
        int sectionStart = markdown.indexOf(sectionHeader);

        if (sectionStart == -1) {
            // section 不存在，创建并追加
            return markdown + "\n\n" + sectionHeader + "\n- " + entry + "\n";
        }

        // 找到下一个 ## 标题的位置
        int contentStart = sectionStart + sectionHeader.length();
        int nextSection = markdown.indexOf("\n## ", contentStart);

        if (nextSection == -1) {
            // 这是最后一个 section，直接追加到末尾
            return markdown + "\n- " + entry;
        } else {
            // 在下一个 section 之前插入
            return markdown.substring(0, nextSection) + "\n- " + entry
                    + markdown.substring(nextSection);
        }
    }
}
