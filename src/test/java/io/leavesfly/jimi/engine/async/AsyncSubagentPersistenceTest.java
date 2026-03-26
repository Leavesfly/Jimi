package io.leavesfly.jimi.engine.async;

import io.leavesfly.jimi.core.async.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异步子代理持久化服务测试
 */
class AsyncSubagentPersistenceTest {
    
    private AsyncSubagentPersistence persistence;
    
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() {
        persistence = new AsyncSubagentPersistence();
    }
    
    @Test
    void testSaveAndLoadRecord() {
        // 创建测试记录
        AsyncSubagentRecord record = AsyncSubagentRecord.builder()
                .id("test-001")
                .name("TestAgent")
                .mode("fire_and_forget")
                .status("completed")
                .startTime(Instant.now().minusSeconds(60))
                .endTime(Instant.now())
                .durationMs(60000)
                .prompt("Test prompt")
                .result("Test result")
                .build();
        
        // 保存记录
        persistence.save(tempDir, record);
        
        // 验证文件存在
        Path resultFile = tempDir.resolve(".jimi/async_subagents/results/test-001.json");
        assertTrue(Files.exists(resultFile), "Result file should exist");
        
        Path indexFile = tempDir.resolve(".jimi/async_subagents/index.json");
        assertTrue(Files.exists(indexFile), "Index file should exist");
        
        // 加载记录
        Optional<AsyncSubagentRecord> loaded = persistence.loadRecord(tempDir, "test-001");
        assertTrue(loaded.isPresent(), "Record should be loaded");
        
        AsyncSubagentRecord loadedRecord = loaded.get();
        assertEquals("test-001", loadedRecord.getId());
        assertEquals("TestAgent", loadedRecord.getName());
        assertEquals("fire_and_forget", loadedRecord.getMode());
        assertEquals("completed", loadedRecord.getStatus());
        assertEquals("Test prompt", loadedRecord.getPrompt());
        assertEquals("Test result", loadedRecord.getResult());
        
        System.out.println("✅ Save and load record test passed");
    }
    
    @Test
    void testGetHistory() {
        // 保存多个记录
        for (int i = 1; i <= 5; i++) {
            AsyncSubagentRecord record = AsyncSubagentRecord.builder()
                    .id("record-" + i)
                    .name("Agent-" + i)
                    .mode("fire_and_forget")
                    .status("completed")
                    .startTime(Instant.now().minusSeconds(100 - i * 10))
                    .endTime(Instant.now().minusSeconds(50 - i * 5))
                    .durationMs(i * 1000L)
                    .prompt("Prompt " + i)
                    .result("Result " + i)
                    .build();
            persistence.save(tempDir, record);
        }
        
        // 获取历史记录（限制3条）
        List<AsyncSubagentRecord> history = persistence.getHistory(tempDir, 3);
        
        assertEquals(3, history.size(), "Should return 3 records");
        // 最新的记录应该在前面
        assertEquals("record-5", history.get(0).getId());
        assertEquals("record-4", history.get(1).getId());
        assertEquals("record-3", history.get(2).getId());
        
        // 获取全部历史
        List<AsyncSubagentRecord> allHistory = persistence.getHistory(tempDir, 10);
        assertEquals(5, allHistory.size(), "Should return all 5 records");
        
        System.out.println("✅ Get history test passed: " + history.size() + " records retrieved");
    }
    
    @Test
    void testClearHistory() {
        // 保存一些记录
        for (int i = 1; i <= 3; i++) {
            AsyncSubagentRecord record = AsyncSubagentRecord.builder()
                    .id("clear-" + i)
                    .name("ClearAgent-" + i)
                    .mode("fire_and_forget")
                    .status("completed")
                    .startTime(Instant.now())
                    .endTime(Instant.now())
                    .durationMs(1000L)
                    .build();
            persistence.save(tempDir, record);
        }
        
        // 验证记录存在
        assertEquals(3, persistence.getHistoryCount(tempDir));
        
        // 清理历史
        int cleared = persistence.clearHistory(tempDir);
        
        assertEquals(3, cleared, "Should clear 3 records");
        assertEquals(0, persistence.getHistoryCount(tempDir), "History should be empty after clear");
        
        // 验证文件被删除
        Path indexFile = tempDir.resolve(".jimi/async_subagents/index.json");
        assertFalse(Files.exists(indexFile), "Index file should be deleted");
        
        System.out.println("✅ Clear history test passed: " + cleared + " records cleared");
    }
    
    @Test
    void testLoadNonExistentRecord() {
        Optional<AsyncSubagentRecord> record = persistence.loadRecord(tempDir, "non-existent");
        assertFalse(record.isPresent(), "Should return empty for non-existent record");
        
        System.out.println("✅ Load non-existent record test passed");
    }
    
    @Test
    void testNullWorkDir() {
        AsyncSubagentRecord record = AsyncSubagentRecord.builder()
                .id("null-test")
                .name("NullTest")
                .build();
        
        // 不应该抛出异常
        persistence.save(null, record);
        
        List<AsyncSubagentRecord> history = persistence.getHistory(null, 10);
        assertTrue(history.isEmpty(), "Should return empty list for null workDir");
        
        int cleared = persistence.clearHistory(null);
        assertEquals(0, cleared, "Should return 0 for null workDir");
        
        System.out.println("✅ Null workDir test passed");
    }
    
    @Test
    void testSaveFromAsyncSubagent() {
        // 创建 AsyncSubagent
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("subagent-001")
                .name("TestSubagent")
                .mode(AsyncSubagentMode.FIRE_AND_FORGET)
                .status(AsyncSubagentStatus.COMPLETED)
                .startTime(Instant.now().minusSeconds(30))
                .endTime(Instant.now())
                .prompt("Execute task")
                .result("Task completed successfully")
                .workDir(tempDir)
                .build();
        
        // 保存
        persistence.save(tempDir, subagent);
        
        // 加载验证
        Optional<AsyncSubagentRecord> loaded = persistence.loadRecord(tempDir, "subagent-001");
        assertTrue(loaded.isPresent());
        
        AsyncSubagentRecord record = loaded.get();
        assertEquals("subagent-001", record.getId());
        assertEquals("TestSubagent", record.getName());
        assertEquals("fire_and_forget", record.getMode());
        assertEquals("completed", record.getStatus());
        assertEquals("Execute task", record.getPrompt());
        assertEquals("Task completed successfully", record.getResult());
        assertTrue(record.getDurationMs() >= 30000);
        
        System.out.println("✅ Save from AsyncSubagent test passed");
    }
    
    @Test
    void testWatchModeRecord() {
        // 创建 Watch 模式记录
        AsyncSubagentRecord record = AsyncSubagentRecord.builder()
                .id("watch-001")
                .name("WatchAgent")
                .mode("watch")
                .status("completed")
                .startTime(Instant.now().minusSeconds(120))
                .endTime(Instant.now())
                .durationMs(120000)
                .prompt("Monitor logs")
                .result("Pattern matched")
                .triggerPattern("ERROR.*Exception")
                .build();
        
        persistence.save(tempDir, record);
        
        Optional<AsyncSubagentRecord> loaded = persistence.loadRecord(tempDir, "watch-001");
        assertTrue(loaded.isPresent());
        
        assertEquals("watch", loaded.get().getMode());
        assertEquals("ERROR.*Exception", loaded.get().getTriggerPattern());
        
        System.out.println("✅ Watch mode record test passed");
    }
    
    @Test
    void testIndexEntryFromRecord() {
        AsyncSubagentRecord record = AsyncSubagentRecord.builder()
                .id("entry-001")
                .name("EntryTest")
                .status("completed")
                .startTime(Instant.now())
                .durationMs(5000)
                .build();
        
        AsyncSubagentPersistence.AsyncSubagentIndexEntry entry = 
                AsyncSubagentPersistence.AsyncSubagentIndexEntry.fromRecord(record);
        
        assertEquals("entry-001", entry.getId());
        assertEquals("EntryTest", entry.getName());
        assertEquals("completed", entry.getStatus());
        assertEquals(5000, entry.getDurationMs());
        assertNotNull(entry.getStartTime());
        
        System.out.println("✅ Index entry from record test passed");
    }
    
    @Test
    void testUpdateExistingRecord() {
        // 先保存一个记录
        AsyncSubagentRecord record1 = AsyncSubagentRecord.builder()
                .id("update-001")
                .name("UpdateAgent")
                .status("running")
                .startTime(Instant.now())
                .durationMs(0)
                .build();
        persistence.save(tempDir, record1);
        
        // 更新记录
        AsyncSubagentRecord record2 = AsyncSubagentRecord.builder()
                .id("update-001")
                .name("UpdateAgent")
                .status("completed")
                .startTime(record1.getStartTime())
                .endTime(Instant.now())
                .durationMs(10000)
                .result("Completed!")
                .build();
        persistence.save(tempDir, record2);
        
        // 验证只有一条记录
        assertEquals(1, persistence.getHistoryCount(tempDir));
        
        // 验证是更新后的内容
        Optional<AsyncSubagentRecord> loaded = persistence.loadRecord(tempDir, "update-001");
        assertTrue(loaded.isPresent());
        assertEquals("completed", loaded.get().getStatus());
        assertEquals("Completed!", loaded.get().getResult());
        
        System.out.println("✅ Update existing record test passed");
    }
}
