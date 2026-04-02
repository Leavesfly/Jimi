package io.leavesfly.jimi.knowledge.memory;

import io.leavesfly.jimi.memory.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {

    @TempDir
    Path tempDir;

    private MemoryStore memoryStore;
    private String workDirPath;

    @BeforeEach
    void setUp() {
        workDirPath = "/Users/test/workspace";
        String customStoragePath = tempDir.toString();
        memoryStore = new MemoryStore(workDirPath, customStoragePath);
    }

    @Test
    void testReadMemoryMd_WhenFileDoesNotExist_ReturnsEmptyString() {
        String content = memoryStore.readMemoryMd();
        assertEquals("", content, "Should return empty string when MEMORY.md does not exist");
    }

    @Test
    void testWriteMemoryMd_ShouldCreateFile() {
        String content = "# Project Memory\n\n## Test Section\nTest content";
        memoryStore.writeMemoryMd(content);

        Path memoryFile = tempDir.resolve("MEMORY.md");
        assertTrue(Files.exists(memoryFile), "MEMORY.md should be created");
    }

    @Test
    void testWriteAndReadMemoryMd_ShouldPersistContent() {
        String content = "# Project Memory\n\n## Test Section\nTest content";
        memoryStore.writeMemoryMd(content);

        String readContent = memoryStore.readMemoryMd();
        assertEquals(content, readContent, "Read content should match written content");
    }

    @Test
    void testWriteMemoryMd_ShouldOverwriteExistingContent() {
        String firstContent = "# First Content";
        memoryStore.writeMemoryMd(firstContent);

        String secondContent = "# Second Content";
        memoryStore.writeMemoryMd(secondContent);

        String readContent = memoryStore.readMemoryMd();
        assertEquals(secondContent, readContent, "Should overwrite existing content");
    }

    @Test
    void testAppendToMemoryMd_WhenFileIsEmpty_ShouldCreateWithContent() {
        String section = "## New Section\nNew content";
        memoryStore.appendToMemoryMd(section);

        String content = memoryStore.readMemoryMd();
        assertEquals(section, content, "Should create file with section content");
    }

    @Test
    void testAppendToMemoryMd_WhenFileExists_ShouldAppendWithDoubleNewline() {
        String existing = "# Existing Content";
        memoryStore.writeMemoryMd(existing);

        String section = "## New Section\nNew content";
        memoryStore.appendToMemoryMd(section);

        String content = memoryStore.readMemoryMd();
        assertTrue(content.contains(existing), "Should contain existing content");
        assertTrue(content.contains(section), "Should contain new section");
        assertTrue(content.contains("\n\n"), "Should have double newline between sections");
    }

    @Test
    void testInitializeIfAbsent_ShouldCreateDefaultTemplate() {
        memoryStore.initializeIfAbsent(workDirPath);

        String content = memoryStore.readMemoryMd();
        assertTrue(content.contains("# Project Memory"), "Should contain project memory header");
        assertTrue(content.contains("## Project Overview"), "Should contain project overview section");
        assertTrue(content.contains("## User Preferences"), "Should contain user preferences section");
        assertTrue(content.contains("## Key Decisions"), "Should contain key decisions section");
        assertTrue(content.contains("## Lessons Learned"), "Should contain lessons learned section");
    }

    @Test
    void testInitializeIfAbsent_WhenFileExists_ShouldNotOverwrite() {
        String existingContent = "# Existing Content\n\n## Custom Section\nCustom data";
        memoryStore.writeMemoryMd(existingContent);

        memoryStore.initializeIfAbsent(workDirPath);

        String content = memoryStore.readMemoryMd();
        assertEquals(existingContent, content, "Should not overwrite existing file");
    }

    @Test
    void testReadTopic_WhenTopicDoesNotExist_ReturnsEmptyString() {
        String content = memoryStore.readTopic("nonexistent");
        assertEquals("", content, "Should return empty string for nonexistent topic");
    }

    @Test
    void testWriteAndReadTopic_ShouldPersistTopicContent() {
        String topicName = "project-structure";
        String content = "# Project Structure\n\n## Main Components\n- Core module\n- UI module";

        memoryStore.writeTopic(topicName, content);

        String readContent = memoryStore.readTopic(topicName);
        assertEquals(content, readContent, "Read topic content should match written content");
    }

    @Test
    void testWriteTopic_ShouldCreateTopicsDirectory() {
        String topicName = "test-topic";
        String content = "Test content";

        memoryStore.writeTopic(topicName, content);

        Path topicsDir = tempDir.resolve("topics");
        assertTrue(Files.isDirectory(topicsDir), "topics/ directory should be created");
    }

    @Test
    void testWriteTopic_ShouldCreateFileWithMdExtension() {
        String topicName = "coding-patterns";
        String content = "Coding patterns content";

        memoryStore.writeTopic(topicName, content);

        Path topicFile = tempDir.resolve("topics").resolve(topicName + ".md");
        assertTrue(Files.isRegularFile(topicFile), "Topic file should be created with .md extension");
    }

    @Test
    void testListTopics_WhenNoTopicsExist_ReturnsEmptyList() {
        List<String> topics = memoryStore.listTopics();
        assertTrue(topics.isEmpty(), "Should return empty list when no topics exist");
    }

    @Test
    void testListTopics_ShouldReturnTopicNamesWithoutExtension() {
        memoryStore.writeTopic("topic1", "Content 1");
        memoryStore.writeTopic("topic2", "Content 2");

        List<String> topics = memoryStore.listTopics();
        assertEquals(2, topics.size(), "Should return 2 topics");
        assertTrue(topics.contains("topic1"), "Should contain topic1");
        assertTrue(topics.contains("topic2"), "Should contain topic2");
    }

    @Test
    void testListTopics_ShouldIgnoreNonMdFiles() throws Exception {
        Path topicsDir = tempDir.resolve("topics");
        Files.createDirectories(topicsDir);
        Files.writeString(topicsDir.resolve("valid-topic.md"), "Valid content");
        Files.writeString(topicsDir.resolve("invalid-file.txt"), "Invalid content");

        List<String> topics = memoryStore.listTopics();
        assertEquals(1, topics.size(), "Should only return .md files");
        assertTrue(topics.contains("valid-topic"), "Should contain valid-topic");
    }

    @Test
    void testDeleteTopic_WhenTopicExists_ShouldReturnTrue() {
        String topicName = "to-delete";
        memoryStore.writeTopic(topicName, "Content");

        boolean deleted = memoryStore.deleteTopic(topicName);
        assertTrue(deleted, "Should return true when topic is deleted");

        String content = memoryStore.readTopic(topicName);
        assertEquals("", content, "Topic should be deleted");
    }

    @Test
    void testDeleteTopic_WhenTopicDoesNotExist_ShouldReturnFalse() {
        boolean deleted = memoryStore.deleteTopic("nonexistent");
        assertFalse(deleted, "Should return false when topic does not exist");
    }

    @Test
    void testGetMemoryRoot_ShouldReturnCorrectPath() {
        Path memoryRoot = memoryStore.getMemoryRoot();
        assertEquals(tempDir, memoryRoot, "Should return the custom storage path");
    }

    @Test
    void testConstructor_WithDefaultStoragePath_ShouldCreateHashedDirectory() {
        MemoryStore defaultStore = new MemoryStore(workDirPath, "");
        Path memoryRoot = defaultStore.getMemoryRoot();

        String dirHash = Integer.toHexString(workDirPath.hashCode());
        assertTrue(memoryRoot.toString().contains(".jimi/memory"), "Should use default .jimi/memory path");
        assertTrue(memoryRoot.toString().contains(dirHash), "Should use hashed directory name");
    }

    @Test
    void testConstructor_WithCustomStoragePath_ShouldUseCustomPath() {
        String customPath = tempDir.toString();
        MemoryStore customStore = new MemoryStore(workDirPath, customPath);
        Path memoryRoot = customStore.getMemoryRoot();

        assertEquals(customPath, memoryRoot.toString(), "Should use custom storage path");
    }
}
