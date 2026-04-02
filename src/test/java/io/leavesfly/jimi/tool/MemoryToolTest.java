package io.leavesfly.jimi.tool;

import io.leavesfly.jimi.config.info.MemoryConfig;
import io.leavesfly.jimi.knowledge.memory.MemoryManager;
import io.leavesfly.jimi.tool.core.MemoryTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryToolTest {

    @TempDir
    Path tempDir;

    private MemoryTool memoryTool;
    private MemoryManager memoryManager;

    @BeforeEach
    void setUp() {
        memoryTool = new MemoryTool();
        MemoryConfig config = new MemoryConfig();
        config.setEnabled(true);
        config.setStoragePath(tempDir.toString());
        config.setMaxMemoryTokens(2000);
        config.setAutoExtract(true);
        config.setAutoConsolidate(true);
        config.setConsolidateMinSessions(5);
        config.setConsolidateMinHours(24);
        memoryManager = new MemoryManager(config);
        memoryTool.setMemoryManager(memoryManager);
        memoryTool.setWorkDirPath(tempDir.toString());
    }

    @Test
    void read_shouldReturnMemoryContent() {
        // First write some content
        memoryManager.writeMemory(tempDir.toString(), "Test Section", "Test content");

        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("read")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isOk());
        assertTrue(result.getOutput().contains("Test Section"));
        assertTrue(result.getOutput().contains("Test content"));
    }

    @Test
    void write_shouldOverwriteSection() {
        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("write")
                .section("User Preferences")
                .content("New preference content")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isOk());
        assertTrue(result.getOutput().contains("User Preferences"));

        // Verify the content was written
        String memoryContent = memoryManager.readMemory(tempDir.toString());
        assertTrue(memoryContent.contains("User Preferences"));
        assertTrue(memoryContent.contains("New preference content"));
    }

    @Test
    void append_shouldAddEntryToSection() {
        // First create a section
        memoryManager.writeMemory(tempDir.toString(), "Key Decisions", "Initial decision");

        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("append")
                .section("Key Decisions")
                .content("Additional decision")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isOk());
        assertTrue(result.getOutput().contains("Additional decision"));

        // Verify the content was appended
        String memoryContent = memoryManager.readMemory(tempDir.toString());
        assertTrue(memoryContent.contains("Initial decision"));
        assertTrue(memoryContent.contains("Additional decision"));
    }

    @Test
    void list_topics_shouldReturnTopicList() throws Exception {
        // Create topic files in the correct location (memoryRoot/topics/)
        // Since config.storagePath = tempDir, memoryRoot = tempDir
        Path topicsDir = tempDir.resolve("topics");
        Files.createDirectories(topicsDir);
        Files.writeString(topicsDir.resolve("project-structure.md"), "Content 1");
        Files.writeString(topicsDir.resolve("api-design.md"), "Content 2");

        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("list_topics")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isOk());
        assertTrue(result.getOutput().contains("project-structure") || result.getOutput().contains("api-design"));
    }

    @Test
    void read_topic_shouldReturnTopicContent() throws Exception {
        // Create a topic file in the correct location
        Path topicsDir = tempDir.resolve("topics");
        Files.createDirectories(topicsDir);
        Files.writeString(topicsDir.resolve("test-topic.md"), "Topic content here");

        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("read_topic")
                .topicName("test-topic")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isOk());
        assertTrue(result.getOutput().contains("Topic content here"));
    }

    @Test
    void write_topic_shouldWriteTopicFile() {
        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("write_topic")
                .topicName("new-topic")
                .content("New topic content")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isOk());
        assertTrue(result.getOutput().contains("new-topic"));

        // Verify the topic was written
        String topicContent = memoryManager.getOrCreateStore(tempDir.toString())
                .readTopic("new-topic");
        assertEquals("New topic content", topicContent);
    }

    @Test
    void shouldReturnErrorWhenNotInitialized() {
        MemoryTool uninitializedTool = new MemoryTool();
        // Don't set memoryManager or workDirPath

        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("read")
                .build();

        ToolResult result = uninitializedTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("not properly initialized") ||
                   result.getMessage().contains("初始化失败"));
    }

    @Test
    void shouldReturnErrorForUnknownAction() {
        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("unknown_action")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("Unknown action") ||
                   result.getMessage().contains("未知操作"));
    }

    @Test
    void write_shouldReturnErrorWhenMissingSection() {
        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("write")
                .content("Some content")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("section is required") ||
                   result.getMessage().contains("缺少 section"));
    }

    @Test
    void write_shouldReturnErrorWhenMissingContent() {
        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("write")
                .section("Test Section")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("content is required") ||
                   result.getMessage().contains("缺少 content"));
    }

    @Test
    void append_shouldReturnErrorWhenMissingSection() {
        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("append")
                .content("Some content")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("section is required") ||
                   result.getMessage().contains("缺少 section"));
    }

    @Test
    void append_shouldReturnErrorWhenMissingContent() {
        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("append")
                .section("Test Section")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("content is required") ||
                   result.getMessage().contains("缺少 content"));
    }

    @Test
    void read_topic_shouldReturnErrorWhenMissingTopicName() {
        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("read_topic")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("topic_name is required") ||
                   result.getMessage().contains("缺少 topic_name"));
    }

    @Test
    void write_topic_shouldReturnErrorWhenMissingTopicName() {
        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("write_topic")
                .content("Some content")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("topic_name is required") ||
                   result.getMessage().contains("缺少 topic_name"));
    }

    @Test
    void write_topic_shouldReturnErrorWhenMissingContent() {
        MemoryTool.Params params = MemoryTool.Params.builder()
                .action("write_topic")
                .topicName("test-topic")
                .build();

        ToolResult result = memoryTool.execute(params).block();

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("content is required") ||
                   result.getMessage().contains("缺少 content"));
    }
}
