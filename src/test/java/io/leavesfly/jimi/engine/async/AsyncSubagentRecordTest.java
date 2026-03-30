package io.leavesfly.jimi.engine.async;

import io.leavesfly.jimi.core.async.AsyncSubagent;
import io.leavesfly.jimi.core.async.AsyncSubagentMode;
import io.leavesfly.jimi.core.async.AsyncSubagentRecord;
import io.leavesfly.jimi.core.async.AsyncSubagentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异步子代理记录实体测试
 */
class AsyncSubagentRecordTest {
    
    @Test
    void testFromSubagentFireAndForget() {
        // 创建 Fire-and-Forget 模式的 AsyncSubagent
        Instant startTime = Instant.now().minusSeconds(45);
        Instant endTime = Instant.now();
        
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("ff-001")
                .name("BackgroundAgent")
                .mode(AsyncSubagentMode.FIRE_AND_FORGET)
                .status(AsyncSubagentStatus.COMPLETED)
                .startTime(startTime)
                .endTime(endTime)
                .prompt("Run background task")
                .result("SubAgentTool completed successfully")
                .build();
        
        // 转换为记录
        AsyncSubagentRecord record = AsyncSubagentRecord.fromSubagent(subagent);
        
        // 验证转换结果
        assertEquals("ff-001", record.getId());
        assertEquals("BackgroundAgent", record.getName());
        assertEquals("fire_and_forget", record.getMode());
        assertEquals("completed", record.getStatus());
        assertEquals(startTime, record.getStartTime());
        assertEquals(endTime, record.getEndTime());
        assertEquals("Run background task", record.getPrompt());
        assertEquals("SubAgentTool completed successfully", record.getResult());
        assertNull(record.getError());
        assertNull(record.getTriggerPattern());
        assertTrue(record.getDurationMs() >= 45000);
        
        System.out.println("✅ Fire-and-Forget record creation test passed");
    }
    
    @Test
    void testFromSubagentWatchMode() {
        // 创建 Watch 模式的 AsyncSubagent
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("watch-001")
                .name("WatchAgent")
                .mode(AsyncSubagentMode.WATCH)
                .status(AsyncSubagentStatus.COMPLETED)
                .startTime(Instant.now().minusSeconds(120))
                .endTime(Instant.now())
                .prompt("Monitor error logs")
                .result("Pattern matched: NullPointerException")
                .triggerPattern(".*Exception.*")
                .build();
        
        AsyncSubagentRecord record = AsyncSubagentRecord.fromSubagent(subagent);
        
        assertEquals("watch", record.getMode());
        assertEquals(".*Exception.*", record.getTriggerPattern());
        assertEquals("Pattern matched: NullPointerException", record.getResult());
        
        System.out.println("✅ Watch mode record creation test passed");
    }
    
    @Test
    void testFromSubagentFailed() {
        // 创建失败的 AsyncSubagent
        RuntimeException error = new RuntimeException("Connection timeout");
        
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("fail-001")
                .name("FailedAgent")
                .mode(AsyncSubagentMode.FIRE_AND_FORGET)
                .status(AsyncSubagentStatus.FAILED)
                .startTime(Instant.now().minusSeconds(10))
                .endTime(Instant.now())
                .prompt("Connect to remote server")
                .error(error)
                .build();
        
        AsyncSubagentRecord record = AsyncSubagentRecord.fromSubagent(subagent);
        
        assertEquals("failed", record.getStatus());
        assertEquals("Connection timeout", record.getError());
        assertNull(record.getResult());
        
        System.out.println("✅ Failed subagent record creation test passed");
    }
    
    @Test
    void testFromSubagentCancelled() {
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("cancel-001")
                .name("CancelledAgent")
                .mode(AsyncSubagentMode.WATCH)
                .status(AsyncSubagentStatus.CANCELLED)
                .startTime(Instant.now().minusSeconds(300))
                .endTime(Instant.now())
                .prompt("Long running watch")
                .build();
        
        AsyncSubagentRecord record = AsyncSubagentRecord.fromSubagent(subagent);
        
        assertEquals("cancelled", record.getStatus());
        assertTrue(record.getDurationMs() >= 300000);
        
        System.out.println("✅ Cancelled subagent record creation test passed");
    }
    
    @Test
    void testFromSubagentTimeout() {
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("timeout-001")
                .name("TimeoutAgent")
                .mode(AsyncSubagentMode.FIRE_AND_FORGET)
                .status(AsyncSubagentStatus.TIMEOUT)
                .startTime(Instant.now().minusSeconds(600))
                .endTime(Instant.now())
                .prompt("SubAgentTool with timeout")
                .error(new java.util.concurrent.TimeoutException("Operation timed out"))
                .build();
        
        AsyncSubagentRecord record = AsyncSubagentRecord.fromSubagent(subagent);
        
        assertEquals("timeout", record.getStatus());
        assertEquals("Operation timed out", record.getError());
        
        System.out.println("✅ Timeout subagent record creation test passed");
    }
    
    @Test
    void testFromSubagentNullValues() {
        // 创建只有必需字段的 AsyncSubagent
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("null-001")
                .name("NullAgent")
                .startTime(Instant.now())
                .build();
        
        AsyncSubagentRecord record = AsyncSubagentRecord.fromSubagent(subagent);
        
        assertEquals("null-001", record.getId());
        assertEquals("NullAgent", record.getName());
        assertNull(record.getMode());
        assertNull(record.getStatus());
        assertNull(record.getPrompt());
        assertNull(record.getResult());
        assertNull(record.getError());
        
        System.out.println("✅ Null values handling test passed");
    }
    
    @Test
    void testFormattedDurationSeconds() {
        AsyncSubagentRecord record = AsyncSubagentRecord.builder()
                .id("duration-1")
                .durationMs(45000)  // 45秒
                .build();
        
        assertEquals("45s", record.getFormattedDuration());
        
        System.out.println("✅ Formatted duration (seconds) test passed");
    }
    
    @Test
    void testFormattedDurationMinutes() {
        AsyncSubagentRecord record = AsyncSubagentRecord.builder()
                .id("duration-2")
                .durationMs(185000)  // 3分05秒
                .build();
        
        assertEquals("3m5s", record.getFormattedDuration());
        
        System.out.println("✅ Formatted duration (minutes) test passed");
    }
    
    @Test
    void testFormattedDurationHours() {
        AsyncSubagentRecord record = AsyncSubagentRecord.builder()
                .id("duration-3")
                .durationMs(3725000)  // 1小时2分5秒
                .build();
        
        assertEquals("1h2m5s", record.getFormattedDuration());
        
        System.out.println("✅ Formatted duration (hours) test passed");
    }
    
    @Test
    void testBuilderAndGetters() {
        Instant now = Instant.now();
        
        AsyncSubagentRecord record = AsyncSubagentRecord.builder()
                .id("builder-001")
                .name("BuilderTest")
                .mode("watch")
                .status("running")
                .startTime(now)
                .endTime(now)
                .durationMs(5000)
                .prompt("Test prompt")
                .result("Test result")
                .error("Test error")
                .triggerPattern(".*pattern.*")
                .build();
        
        // 验证所有 getter
        assertEquals("builder-001", record.getId());
        assertEquals("BuilderTest", record.getName());
        assertEquals("watch", record.getMode());
        assertEquals("running", record.getStatus());
        assertEquals(now, record.getStartTime());
        assertEquals(now, record.getEndTime());
        assertEquals(5000, record.getDurationMs());
        assertEquals("Test prompt", record.getPrompt());
        assertEquals("Test result", record.getResult());
        assertEquals("Test error", record.getError());
        assertEquals(".*pattern.*", record.getTriggerPattern());
        
        System.out.println("✅ Builder and getters test passed");
    }
    
    @Test
    void testSetters() {
        AsyncSubagentRecord record = new AsyncSubagentRecord();
        
        record.setId("setter-001");
        record.setName("SetterTest");
        record.setMode("fire_and_forget");
        record.setStatus("completed");
        record.setDurationMs(10000);
        record.setPrompt("Setter prompt");
        record.setResult("Setter result");
        
        assertEquals("setter-001", record.getId());
        assertEquals("SetterTest", record.getName());
        assertEquals("fire_and_forget", record.getMode());
        assertEquals("completed", record.getStatus());
        assertEquals(10000, record.getDurationMs());
        assertEquals("Setter prompt", record.getPrompt());
        assertEquals("Setter result", record.getResult());
        
        System.out.println("✅ Setters test passed");
    }
}
