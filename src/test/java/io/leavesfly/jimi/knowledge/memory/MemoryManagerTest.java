package io.leavesfly.jimi.knowledge.memory;

import io.leavesfly.jimi.config.info.MemoryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest {

    @TempDir
    Path tempDir;

    private MemoryManager memoryManager;
    private String workDirPath;

    @BeforeEach
    void setUp() {
        MemoryConfig config = new MemoryConfig();
        config.setEnabled(true);
        config.setStoragePath(tempDir.toString());
        config.setMaxMemoryTokens(2000);
        config.setAutoExtract(true);
        config.setAutoConsolidate(true);
        config.setConsolidateMinSessions(5);
        config.setConsolidateMinHours(24);

        memoryManager = new MemoryManager(config);
        workDirPath = "/Users/test/workspace";
    }

    @Test
    void testGetMemorySummary_WhenMemoryDisabled_ReturnsEmptyString() {
        MemoryConfig disabledConfig = new MemoryConfig();
        disabledConfig.setEnabled(false);
        MemoryManager disabledManager = new MemoryManager(disabledConfig);

        String summary = disabledManager.getMemorySummary(workDirPath);
        assertEquals("", summary, "Should return empty string when memory is disabled");
    }

    @Test
    void testGetMemorySummary_WhenMemoryFileDoesNotExist_ShouldInitializeAndReturnFormattedContent() {
        String summary = memoryManager.getMemorySummary(workDirPath);

        assertNotNull(summary, "Summary should not be null");
        assertTrue(summary.contains("## Long-term Memory"), "Should contain long-term memory header");
        assertTrue(summary.contains("# Project Memory"), "Should contain project memory header");
        assertTrue(summary.contains("## Project Overview"), "Should contain project overview section");
    }

    @Test
    void testGetMemorySummary_ShouldFormatContentForSystemPrompt() {
        String customContent = "# Custom Memory\n\n## Custom Section\nCustom data";
        memoryManager.overwriteMemory(workDirPath, customContent);

        String summary = memoryManager.getMemorySummary(workDirPath);

        assertTrue(summary.contains("## Long-term Memory"), "Should contain long-term memory header");
        assertTrue(summary.contains("persistent memory about this project"), "Should contain description");
        assertTrue(summary.contains(customContent), "Should contain original memory content");
    }

    @Test
    void testGetMemorySummary_WhenMemoryIsEmpty_ShouldReturnEmptyString() {
        memoryManager.overwriteMemory(workDirPath, "");
        String summary = memoryManager.getMemorySummary(workDirPath);
        assertEquals("", summary, "Should return empty string when memory content is empty");
    }

    @Test
    void testWriteMemory_WhenMemoryDisabled_ShouldDoNothing() {
        MemoryConfig disabledConfig = new MemoryConfig();
        disabledConfig.setEnabled(false);
        MemoryManager disabledManager = new MemoryManager(disabledConfig);

        disabledManager.writeMemory(workDirPath, "Test Section", "Test content");

        String memory = disabledManager.readMemory(workDirPath);
        assertEquals("", memory, "Should not write when memory is disabled");
    }

    @Test
    void testWriteMemory_ShouldCreateNewSection() {
        memoryManager.writeMemory(workDirPath, "Test Section", "Test content");

        String memory = memoryManager.readMemory(workDirPath);
        assertTrue(memory.contains("## Test Section"), "Should contain new section header");
        assertTrue(memory.contains("Test content"), "Should contain section content");
    }

    @Test
    void testWriteMemory_ShouldUpdateExistingSection() {
        String initialContent = "# Project Memory\n\n## Test Section\nInitial content";
        memoryManager.overwriteMemory(workDirPath, initialContent);

        memoryManager.writeMemory(workDirPath, "Test Section", "Updated content");

        String memory = memoryManager.readMemory(workDirPath);
        assertTrue(memory.contains("## Test Section"), "Should contain section header");
        assertTrue(memory.contains("Updated content"), "Should contain updated content");
        assertFalse(memory.contains("Initial content"), "Should not contain old content");
    }

    @Test
    void testWriteMemory_ShouldAppendNewSectionToEnd() {
        String initialContent = "# Project Memory\n\n## Existing Section\nExisting content";
        memoryManager.overwriteMemory(workDirPath, initialContent);

        memoryManager.writeMemory(workDirPath, "New Section", "New content");

        String memory = memoryManager.readMemory(workDirPath);
        assertTrue(memory.contains("## Existing Section"), "Should contain existing section");
        assertTrue(memory.contains("## New Section"), "Should contain new section");
        assertTrue(memory.indexOf("## New Section") > memory.indexOf("## Existing Section"), 
                   "New section should be after existing section");
    }

    @Test
    void testAppendMemory_WhenMemoryDisabled_ShouldDoNothing() {
        MemoryConfig disabledConfig = new MemoryConfig();
        disabledConfig.setEnabled(false);
        MemoryManager disabledManager = new MemoryManager(disabledConfig);

        disabledManager.appendMemory(workDirPath, "Test Section", "Test entry");

        String memory = disabledManager.readMemory(workDirPath);
        assertEquals("", memory, "Should not append when memory is disabled");
    }

    @Test
    void testAppendMemory_ShouldCreateNewSectionWithEntry() {
        memoryManager.appendMemory(workDirPath, "Test Section", "Test entry");

        String memory = memoryManager.readMemory(workDirPath);
        assertTrue(memory.contains("## Test Section"), "Should contain section header");
        assertTrue(memory.contains("- Test entry"), "Should contain entry as list item");
    }

    @Test
    void testAppendMemory_ShouldAppendToExistingSection() {
        String initialContent = "# Project Memory\n\n## Test Section\n- First entry";
        memoryManager.overwriteMemory(workDirPath, initialContent);

        memoryManager.appendMemory(workDirPath, "Test Section", "Second entry");

        String memory = memoryManager.readMemory(workDirPath);
        assertTrue(memory.contains("- First entry"), "Should contain first entry");
        assertTrue(memory.contains("- Second entry"), "Should contain second entry");
    }

    @Test
    void testAppendMemory_ShouldAppendBeforeNextSection() {
        String initialContent = "# Project Memory\n\n## First Section\n- First entry\n\n## Second Section\nSecond content";
        memoryManager.overwriteMemory(workDirPath, initialContent);

        memoryManager.appendMemory(workDirPath, "First Section", "New entry");

        String memory = memoryManager.readMemory(workDirPath);
        assertTrue(memory.contains("- First entry"), "Should contain first entry");
        assertTrue(memory.contains("- New entry"), "Should contain new entry");
        assertTrue(memory.contains("## Second Section"), "Should contain second section");
    }

    @Test
    void testReadMemory_WhenMemoryDisabled_ReturnsEmptyString() {
        MemoryConfig disabledConfig = new MemoryConfig();
        disabledConfig.setEnabled(false);
        MemoryManager disabledManager = new MemoryManager(disabledConfig);

        String memory = disabledManager.readMemory(workDirPath);
        assertEquals("", memory, "Should return empty string when memory is disabled");
    }

    @Test
    void testReadMemory_ShouldReturnMemoryContent() {
        String expectedContent = "# Project Memory\n\n## Test Section\nTest content";
        memoryManager.overwriteMemory(workDirPath, expectedContent);

        String memory = memoryManager.readMemory(workDirPath);
        assertEquals(expectedContent, memory, "Should return memory content");
    }

    @Test
    void testOverwriteMemory_WhenMemoryDisabled_ShouldDoNothing() {
        MemoryConfig disabledConfig = new MemoryConfig();
        disabledConfig.setEnabled(false);
        MemoryManager disabledManager = new MemoryManager(disabledConfig);

        disabledManager.overwriteMemory(workDirPath, "New content");

        String memory = disabledManager.readMemory(workDirPath);
        assertEquals("", memory, "Should not overwrite when memory is disabled");
    }

    @Test
    void testOverwriteMemory_ShouldReplaceEntireContent() {
        String initialContent = "# Initial Content\n\n## Section 1\nContent 1";
        memoryManager.overwriteMemory(workDirPath, initialContent);

        String newContent = "# New Content\n\n## Section 2\nContent 2";
        memoryManager.overwriteMemory(workDirPath, newContent);

        String memory = memoryManager.readMemory(workDirPath);
        assertEquals(newContent, memory, "Should replace entire content");
        assertFalse(memory.contains("Initial Content"), "Should not contain old content");
    }

    @Test
    void testGetOrCreateStore_ShouldCreateNewStoreForNewWorkDir() {
        MemoryStore store = memoryManager.getOrCreateStore(workDirPath);

        assertNotNull(store, "Store should not be null");
        assertEquals(tempDir, store.getMemoryRoot(), "Store should use custom storage path");
    }

    @Test
    void testGetOrCreateStore_ShouldReturnCachedStoreForSameWorkDir() {
        MemoryStore firstStore = memoryManager.getOrCreateStore(workDirPath);
        MemoryStore secondStore = memoryManager.getOrCreateStore(workDirPath);

        assertSame(firstStore, secondStore, "Should return same cached store instance");
    }

    @Test
    void testGetOrCreateStore_ShouldCreateDifferentStoresForDifferentWorkDirs() {
        String workDirPath1 = "/Users/test/workspace1";
        String workDirPath2 = "/Users/test/workspace2";

        MemoryStore store1 = memoryManager.getOrCreateStore(workDirPath1);
        MemoryStore store2 = memoryManager.getOrCreateStore(workDirPath2);

        assertNotSame(store1, store2, "Should create different stores for different work directories");
    }

    @Test
    void testGetOrCreateStore_ShouldUseConfiguredStoragePath() {
        MemoryConfig config = new MemoryConfig();
        config.setEnabled(true);
        config.setStoragePath(tempDir.toString());
        MemoryManager manager = new MemoryManager(config);

        MemoryStore store = manager.getOrCreateStore(workDirPath);

        assertEquals(tempDir, store.getMemoryRoot(), "Store should use configured storage path");
    }

    @Test
    void testGetConfig_ShouldReturnConfiguredConfig() {
        MemoryConfig config = memoryManager.getConfig();

        assertNotNull(config, "Config should not be null");
        assertTrue(config.isEnabled(), "Config should be enabled");
        assertEquals(2000, config.getMaxMemoryTokens(), "Max memory tokens should match");
        assertTrue(config.isAutoExtract(), "Auto extract should be enabled");
    }

    @Test
    void testMultipleWorkDirs_ShouldMaintainSeparateMemories() {
        // Use empty storagePath so each workDir gets its own hash-based directory
        MemoryConfig isolatedConfig = new MemoryConfig();
        isolatedConfig.setEnabled(true);
        isolatedConfig.setStoragePath("");
        isolatedConfig.setMaxMemoryTokens(2000);
        MemoryManager isolatedManager = new MemoryManager(isolatedConfig);

        String workDirPath1 = tempDir.resolve("project1").toString();
        String workDirPath2 = tempDir.resolve("project2").toString();

        isolatedManager.writeMemory(workDirPath1, "Project 1 Section", "Project 1 content");
        isolatedManager.writeMemory(workDirPath2, "Project 2 Section", "Project 2 content");

        String memory1 = isolatedManager.readMemory(workDirPath1);
        String memory2 = isolatedManager.readMemory(workDirPath2);

        assertTrue(memory1.contains("Project 1 content"), "Should contain project 1 content");
        assertTrue(memory2.contains("Project 2 content"), "Should contain project 2 content");
        assertFalse(memory1.contains("Project 2 content"), "Should not contain project 2 content");
        assertFalse(memory2.contains("Project 1 content"), "Should not contain project 1 content");
    }

    @Test
    void testInitializeIfAbsent_ShouldNotOverwriteExistingMemory() {
        String existingContent = "# Existing Memory\n\n## Existing Section\nExisting content";
        memoryManager.overwriteMemory(workDirPath, existingContent);

        memoryManager.getMemorySummary(workDirPath);

        String memory = memoryManager.readMemory(workDirPath);
        assertEquals(existingContent, memory, "Should not overwrite existing memory");
    }

    @Test
    void testSectionUpdate_WhenSectionIsLast_ShouldUpdateCorrectly() {
        String content = "# Project Memory\n\n## Section 1\nContent 1\n\n## Section 2\nOld section two data";
        memoryManager.overwriteMemory(workDirPath, content);

        memoryManager.writeMemory(workDirPath, "Section 2", "New section two data");

        String memory = memoryManager.readMemory(workDirPath);
        assertTrue(memory.contains("## Section 1"), "Should contain Section 1");
        assertTrue(memory.contains("New section two data"), "Should contain updated Section 2");
        assertFalse(memory.contains("Old section two data"), "Should not contain old Section 2 content");
    }

    @Test
    void testSectionAppend_WhenSectionIsLast_ShouldAppendToEnd() {
        String content = "# Project Memory\n\n## Section 1\nContent 1";
        memoryManager.overwriteMemory(workDirPath, content);

        memoryManager.appendMemory(workDirPath, "Section 1", "New entry");

        String memory = memoryManager.readMemory(workDirPath);
        assertTrue(memory.contains("Content 1"), "Should contain original content");
        assertTrue(memory.contains("- New entry"), "Should contain new entry");
        assertTrue(memory.indexOf("- New entry") > memory.indexOf("Content 1"), 
                   "New entry should be after original content");
    }
}
