package io.leavesfly.jimi.memory;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 记忆存储层
 * <p>
 * 负责 MEMORY.md 和 Topic files 的文件读写操作。
 * 存储路径：~/.jimi/memory/{dirHash}/
 * <p>
 * 文件结构：
 * <pre>
 * ~/.jimi/memory/{dirHash}/
 * ├── MEMORY.md              ← Layer 1: 索引层，始终注入 System Prompt
 * └── topics/                ← Layer 2: 主题文件层，按需加载
 *     ├── project-structure.md
 *     └── coding-patterns.md
 * </pre>
 */
@Slf4j
public class MemoryStore {

    private static final String MEMORY_DIR = "memory";
    private static final String MEMORY_FILE = "MEMORY.md";
    private static final String TOPICS_DIR = "topics";

    private final Path memoryRoot;

    /**
     * 构造 MemoryStore
     *
     * @param workDirPath 工作目录的绝对路径字符串
     * @param customStoragePath 自定义存储路径（为空则使用默认 ~/.jimi/memory/{dirHash}）
     */
    public MemoryStore(String workDirPath, String customStoragePath) {
        if (customStoragePath != null && !customStoragePath.isEmpty()) {
            this.memoryRoot = Paths.get(customStoragePath);
        } else {
            String dirHash = Integer.toHexString(workDirPath.hashCode());
            this.memoryRoot = Paths.get(System.getProperty("user.home"), ".jimi", MEMORY_DIR, dirHash);
        }
        ensureDirectories();
    }

    /**
     * 读取 MEMORY.md 内容
     *
     * @return MEMORY.md 的内容，如果文件不存在则返回空字符串
     */
    public String readMemoryMd() {
        Path memoryFile = memoryRoot.resolve(MEMORY_FILE);
        if (!Files.isRegularFile(memoryFile)) {
            return "";
        }
        try {
            return Files.readString(memoryFile).trim();
        } catch (IOException e) {
            log.warn("Failed to read MEMORY.md: {}", memoryFile, e);
            return "";
        }
    }

    /**
     * 写入 MEMORY.md 内容（完整覆盖）
     *
     * @param content 要写入的内容
     */
    public void writeMemoryMd(String content) {
        Path memoryFile = memoryRoot.resolve(MEMORY_FILE);
        try {
            ensureDirectories();
            Files.writeString(memoryFile, content);
            log.debug("Written MEMORY.md: {} bytes", content.length());
        } catch (IOException e) {
            log.error("Failed to write MEMORY.md: {}", memoryFile, e);
        }
    }

    /**
     * 追加内容到 MEMORY.md
     *
     * @param section 要追加的内容段落
     */
    public void appendToMemoryMd(String section) {
        String existing = readMemoryMd();
        String updated = existing.isEmpty() ? section : existing + "\n\n" + section;
        writeMemoryMd(updated);
    }

    /**
     * 初始化 MEMORY.md（如果不存在则创建默认模板）
     *
     * @param workDirPath 工作目录路径（用于显示项目名）
     */
    public void initializeIfAbsent(String workDirPath) {
        Path memoryFile = memoryRoot.resolve(MEMORY_FILE);
        if (Files.isRegularFile(memoryFile)) {
            return;
        }

        String projectName = Paths.get(workDirPath).getFileName().toString();
        String now = ZonedDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        String template = "# Project Memory\n\n"
                + "## Project Overview\n"
                + "- **Project**: " + projectName + "\n"
                + "- **Initialized**: " + now + "\n\n"
                + "## User Preferences\n\n"
                + "## Key Decisions\n\n"
                + "## Lessons Learned\n";

        writeMemoryMd(template);
        log.info("Initialized MEMORY.md for project: {}", projectName);
    }

    /**
     * 读取指定 Topic 文件
     *
     * @param topicName 主题名称（不含 .md 后缀）
     * @return 主题文件内容，如果不存在则返回空字符串
     */
    public String readTopic(String topicName) {
        Path topicFile = memoryRoot.resolve(TOPICS_DIR).resolve(topicName + ".md");
        if (!Files.isRegularFile(topicFile)) {
            return "";
        }
        try {
            return Files.readString(topicFile).trim();
        } catch (IOException e) {
            log.warn("Failed to read topic file: {}", topicFile, e);
            return "";
        }
    }

    /**
     * 写入 Topic 文件
     *
     * @param topicName 主题名称（不含 .md 后缀）
     * @param content   主题内容
     */
    public void writeTopic(String topicName, String content) {
        Path topicFile = memoryRoot.resolve(TOPICS_DIR).resolve(topicName + ".md");
        try {
            Files.createDirectories(topicFile.getParent());
            Files.writeString(topicFile, content);
            log.debug("Written topic file: {}", topicFile);
        } catch (IOException e) {
            log.error("Failed to write topic file: {}", topicFile, e);
        }
    }

    /**
     * 列出所有 Topic 文件名
     *
     * @return Topic 名称列表（不含 .md 后缀）
     */
    public List<String> listTopics() {
        Path topicsDir = memoryRoot.resolve(TOPICS_DIR);
        if (!Files.isDirectory(topicsDir)) {
            return Collections.emptyList();
        }
        try (Stream<Path> paths = Files.list(topicsDir)) {
            return paths
                    .filter(p -> p.toString().endsWith(".md"))
                    .map(p -> {
                        String fileName = p.getFileName().toString();
                        return fileName.substring(0, fileName.length() - 3);
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Failed to list topics: {}", topicsDir, e);
            return Collections.emptyList();
        }
    }

    /**
     * 删除指定 Topic 文件
     *
     * @param topicName 主题名称
     * @return 是否删除成功
     */
    public boolean deleteTopic(String topicName) {
        Path topicFile = memoryRoot.resolve(TOPICS_DIR).resolve(topicName + ".md");
        try {
            return Files.deleteIfExists(topicFile);
        } catch (IOException e) {
            log.warn("Failed to delete topic: {}", topicFile, e);
            return false;
        }
    }

    /**
     * 获取记忆存储根目录
     */
    public Path getMemoryRoot() {
        return memoryRoot;
    }

    /**
     * 确保目录结构存在
     */
    private void ensureDirectories() {
        try {
            Files.createDirectories(memoryRoot);
            Files.createDirectories(memoryRoot.resolve(TOPICS_DIR));
        } catch (IOException e) {
            log.error("Failed to create memory directories: {}", memoryRoot, e);
        }
    }
}
