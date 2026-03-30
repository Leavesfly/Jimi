package io.leavesfly.jimi.engine.async;

import io.leavesfly.jimi.core.async.AsyncSubagent;
import io.leavesfly.jimi.core.async.AsyncSubagentCallback;
import io.leavesfly.jimi.core.async.AsyncSubagentMode;
import io.leavesfly.jimi.core.async.AsyncSubagentStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异步子代理管理器测试
 * 测试核心实体和枚举的功能，Manager 完整功能需要 Spring 集成测试
 */
class AsyncSubagentManagerTest {
    
    // ==================== AsyncSubagentStatus 测试 ====================
    
    @Test
    void testStatusValues() {
        assertEquals("pending", AsyncSubagentStatus.PENDING.getValue());
        assertEquals("running", AsyncSubagentStatus.RUNNING.getValue());
        assertEquals("completed", AsyncSubagentStatus.COMPLETED.getValue());
        assertEquals("failed", AsyncSubagentStatus.FAILED.getValue());
        assertEquals("cancelled", AsyncSubagentStatus.CANCELLED.getValue());
        assertEquals("timeout", AsyncSubagentStatus.TIMEOUT.getValue());
        
        System.out.println("✅ Status values test passed");
    }
    
    @Test
    void testStatusDisplayNames() {
        assertEquals("等待中", AsyncSubagentStatus.PENDING.getDisplayName());
        assertEquals("运行中", AsyncSubagentStatus.RUNNING.getDisplayName());
        assertEquals("已完成", AsyncSubagentStatus.COMPLETED.getDisplayName());
        assertEquals("已失败", AsyncSubagentStatus.FAILED.getDisplayName());
        assertEquals("已取消", AsyncSubagentStatus.CANCELLED.getDisplayName());
        assertEquals("已超时", AsyncSubagentStatus.TIMEOUT.getDisplayName());
        
        System.out.println("✅ Status display names test passed");
    }
    
    @Test
    void testStatusIsTerminal() {
        // 非终态
        assertFalse(AsyncSubagentStatus.PENDING.isTerminal());
        assertFalse(AsyncSubagentStatus.RUNNING.isTerminal());
        
        // 终态
        assertTrue(AsyncSubagentStatus.COMPLETED.isTerminal());
        assertTrue(AsyncSubagentStatus.FAILED.isTerminal());
        assertTrue(AsyncSubagentStatus.CANCELLED.isTerminal());
        assertTrue(AsyncSubagentStatus.TIMEOUT.isTerminal());
        
        System.out.println("✅ Status terminal check test passed");
    }
    
    // ==================== AsyncSubagentMode 测试 ====================
    
    @Test
    void testModeValues() {
        assertEquals("fire_and_forget", AsyncSubagentMode.FIRE_AND_FORGET.getValue());
        assertEquals("watch", AsyncSubagentMode.WATCH.getValue());
        assertEquals("wait_complete", AsyncSubagentMode.WAIT_COMPLETE.getValue());
        
        System.out.println("✅ Mode values test passed");
    }
    
    @Test
    void testModeDisplayNames() {
        assertEquals("后台运行", AsyncSubagentMode.FIRE_AND_FORGET.getDisplayName());
        assertEquals("持续监控", AsyncSubagentMode.WATCH.getDisplayName());
        assertEquals("等待完成", AsyncSubagentMode.WAIT_COMPLETE.getDisplayName());
        
        System.out.println("✅ Mode display names test passed");
    }
    
    @Test
    void testModeFromValue() {
        assertEquals(AsyncSubagentMode.FIRE_AND_FORGET, AsyncSubagentMode.fromValue("fire_and_forget"));
        assertEquals(AsyncSubagentMode.WATCH, AsyncSubagentMode.fromValue("watch"));
        assertEquals(AsyncSubagentMode.WAIT_COMPLETE, AsyncSubagentMode.fromValue("wait_complete"));
        
        // 大小写不敏感
        assertEquals(AsyncSubagentMode.WATCH, AsyncSubagentMode.fromValue("WATCH"));
        assertEquals(AsyncSubagentMode.WATCH, AsyncSubagentMode.fromValue("Watch"));
        
        // 未知值默认返回 FIRE_AND_FORGET
        assertEquals(AsyncSubagentMode.FIRE_AND_FORGET, AsyncSubagentMode.fromValue("unknown"));
        assertEquals(AsyncSubagentMode.FIRE_AND_FORGET, AsyncSubagentMode.fromValue(null));
        
        System.out.println("✅ Mode fromValue test passed");
    }
    
    // ==================== AsyncSubagent 实体测试 ====================
    
    @Test
    void testAsyncSubagentBuilder() {
        Instant startTime = Instant.now();
        
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("test-001")
                .name("TestSubagent")
                .mode(AsyncSubagentMode.FIRE_AND_FORGET)
                .status(AsyncSubagentStatus.PENDING)
                .startTime(startTime)
                .prompt("Test prompt")
                .timeout(Duration.ofMinutes(5))
                .build();
        
        assertEquals("test-001", subagent.getId());
        assertEquals("TestSubagent", subagent.getName());
        assertEquals(AsyncSubagentMode.FIRE_AND_FORGET, subagent.getMode());
        assertEquals(AsyncSubagentStatus.PENDING, subagent.getStatus());
        assertEquals(startTime, subagent.getStartTime());
        assertEquals("Test prompt", subagent.getPrompt());
        assertEquals(Duration.ofMinutes(5), subagent.getTimeout());
        
        System.out.println("✅ AsyncSubagent builder test passed");
    }
    
    @Test
    void testAsyncSubagentRunningDuration() throws InterruptedException {
        Instant startTime = Instant.now().minusSeconds(30);
        
        // 未完成 - 计算到当前时间
        AsyncSubagent running = AsyncSubagent.builder()
                .id("running-001")
                .startTime(startTime)
                .build();
        
        Duration runningDuration = running.getRunningDuration();
        assertTrue(runningDuration.getSeconds() >= 30, "Running duration should be at least 30 seconds");
        
        // 已完成 - 计算到结束时间
        AsyncSubagent completed = AsyncSubagent.builder()
                .id("completed-001")
                .startTime(startTime)
                .endTime(startTime.plusSeconds(60))
                .build();
        
        Duration completedDuration = completed.getRunningDuration();
        assertEquals(60, completedDuration.getSeconds(), "Completed duration should be exactly 60 seconds");
        
        System.out.println("✅ AsyncSubagent running duration test passed");
    }
    
    @Test
    void testAsyncSubagentIsCompleted() {
        // 非终态
        AsyncSubagent pending = AsyncSubagent.builder()
                .id("pending")
                .status(AsyncSubagentStatus.PENDING)
                .startTime(Instant.now())
                .build();
        assertFalse(pending.isCompleted());
        
        AsyncSubagent running = AsyncSubagent.builder()
                .id("running")
                .status(AsyncSubagentStatus.RUNNING)
                .startTime(Instant.now())
                .build();
        assertFalse(running.isCompleted());
        
        // 终态
        AsyncSubagent completed = AsyncSubagent.builder()
                .id("completed")
                .status(AsyncSubagentStatus.COMPLETED)
                .startTime(Instant.now())
                .build();
        assertTrue(completed.isCompleted());
        
        AsyncSubagent failed = AsyncSubagent.builder()
                .id("failed")
                .status(AsyncSubagentStatus.FAILED)
                .startTime(Instant.now())
                .build();
        assertTrue(failed.isCompleted());
        
        AsyncSubagent cancelled = AsyncSubagent.builder()
                .id("cancelled")
                .status(AsyncSubagentStatus.CANCELLED)
                .startTime(Instant.now())
                .build();
        assertTrue(cancelled.isCompleted());
        
        AsyncSubagent timeout = AsyncSubagent.builder()
                .id("timeout")
                .status(AsyncSubagentStatus.TIMEOUT)
                .startTime(Instant.now())
                .build();
        assertTrue(timeout.isCompleted());
        
        // null 状态
        AsyncSubagent nullStatus = AsyncSubagent.builder()
                .id("null-status")
                .startTime(Instant.now())
                .build();
        assertFalse(nullStatus.isCompleted());
        
        System.out.println("✅ AsyncSubagent isCompleted test passed");
    }
    
    @Test
    void testAsyncSubagentIsSuccess() {
        AsyncSubagent success = AsyncSubagent.builder()
                .id("success")
                .status(AsyncSubagentStatus.COMPLETED)
                .startTime(Instant.now())
                .build();
        assertTrue(success.isSuccess());
        
        AsyncSubagent failed = AsyncSubagent.builder()
                .id("failed")
                .status(AsyncSubagentStatus.FAILED)
                .startTime(Instant.now())
                .build();
        assertFalse(failed.isSuccess());
        
        AsyncSubagent running = AsyncSubagent.builder()
                .id("running")
                .status(AsyncSubagentStatus.RUNNING)
                .startTime(Instant.now())
                .build();
        assertFalse(running.isSuccess());
        
        System.out.println("✅ AsyncSubagent isSuccess test passed");
    }
    
    @Test
    void testAsyncSubagentSetters() {
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("setter-test")
                .startTime(Instant.now())
                .build();
        
        // 测试 setter
        subagent.setStatus(AsyncSubagentStatus.RUNNING);
        assertEquals(AsyncSubagentStatus.RUNNING, subagent.getStatus());
        
        subagent.setResult("Test result");
        assertEquals("Test result", subagent.getResult());
        
        RuntimeException error = new RuntimeException("Test error");
        subagent.setError(error);
        assertEquals(error, subagent.getError());
        
        Instant endTime = Instant.now();
        subagent.setEndTime(endTime);
        assertEquals(endTime, subagent.getEndTime());
        
        System.out.println("✅ AsyncSubagent setters test passed");
    }
    
    @Test
    void testAsyncSubagentWatchMode() {
        AsyncSubagent watchSubagent = AsyncSubagent.builder()
                .id("watch-001")
                .name("WatchAgent")
                .mode(AsyncSubagentMode.WATCH)
                .status(AsyncSubagentStatus.RUNNING)
                .startTime(Instant.now())
                .prompt("Monitor error logs")
                .triggerPattern("ERROR.*Exception")
                .build();
        
        assertEquals(AsyncSubagentMode.WATCH, watchSubagent.getMode());
        assertEquals("ERROR.*Exception", watchSubagent.getTriggerPattern());
        
        System.out.println("✅ AsyncSubagent watch mode test passed");
    }
    
    // ==================== AsyncSubagentCallback 测试 ====================
    
    @Test
    void testAsyncSubagentCallback() {
        // 创建测试回调
        final boolean[] callbackInvoked = {false};
        final AsyncSubagent[] receivedSubagent = {null};
        
        AsyncSubagentCallback callback = subagent -> {
            callbackInvoked[0] = true;
            receivedSubagent[0] = subagent;
        };
        
        // 创建 AsyncSubagent 并绑定回调
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("callback-test")
                .name("CallbackAgent")
                .status(AsyncSubagentStatus.COMPLETED)
                .startTime(Instant.now())
                .callback(callback)
                .build();
        
        // 执行回调
        subagent.getCallback().onComplete(subagent);
        
        assertTrue(callbackInvoked[0], "Callback should be invoked");
        assertEquals("callback-test", receivedSubagent[0].getId());
        
        System.out.println("✅ AsyncSubagent callback test passed");
    }
    
    // ==================== 综合场景测试 ====================
    
    @Test
    void testLifecycleSimulation() {
        // 模拟完整生命周期
        
        // 1. 创建 (PENDING)
        Instant startTime = Instant.now();
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("lifecycle-001")
                .name("LifecycleAgent")
                .mode(AsyncSubagentMode.FIRE_AND_FORGET)
                .status(AsyncSubagentStatus.PENDING)
                .startTime(startTime)
                .prompt("Execute background task")
                .build();
        
        assertFalse(subagent.isCompleted());
        assertFalse(subagent.isSuccess());
        
        // 2. 启动 (RUNNING)
        subagent.setStatus(AsyncSubagentStatus.RUNNING);
        assertFalse(subagent.isCompleted());
        
        // 3. 完成 (COMPLETED)
        subagent.setStatus(AsyncSubagentStatus.COMPLETED);
        subagent.setEndTime(Instant.now());
        subagent.setResult("SubAgentTool completed successfully");
        
        assertTrue(subagent.isCompleted());
        assertTrue(subagent.isSuccess());
        assertNotNull(subagent.getResult());
        assertTrue(subagent.getRunningDuration().toMillis() >= 0);
        
        System.out.println("✅ Lifecycle simulation test passed");
    }
    
    @Test
    void testFailureScenario() {
        // 模拟失败场景
        
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("failure-001")
                .name("FailureAgent")
                .mode(AsyncSubagentMode.FIRE_AND_FORGET)
                .status(AsyncSubagentStatus.RUNNING)
                .startTime(Instant.now())
                .prompt("SubAgentTool that will fail")
                .build();
        
        // 模拟执行失败
        RuntimeException error = new RuntimeException("Connection refused");
        subagent.setStatus(AsyncSubagentStatus.FAILED);
        subagent.setEndTime(Instant.now());
        subagent.setError(error);
        
        assertTrue(subagent.isCompleted());
        assertFalse(subagent.isSuccess());
        assertEquals("Connection refused", subagent.getError().getMessage());
        
        System.out.println("✅ Failure scenario test passed");
    }
    
    @Test
    void testTimeoutScenario() {
        // 模拟超时场景
        
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("timeout-001")
                .name("TimeoutAgent")
                .mode(AsyncSubagentMode.FIRE_AND_FORGET)
                .status(AsyncSubagentStatus.RUNNING)
                .startTime(Instant.now().minus(Duration.ofMinutes(10)))
                .prompt("Long running task")
                .timeout(Duration.ofMinutes(5))
                .build();
        
        // 模拟超时
        subagent.setStatus(AsyncSubagentStatus.TIMEOUT);
        subagent.setEndTime(Instant.now());
        subagent.setError(new java.util.concurrent.TimeoutException("Operation timed out after 5 minutes"));
        
        assertTrue(subagent.isCompleted());
        assertFalse(subagent.isSuccess());
        assertEquals(AsyncSubagentStatus.TIMEOUT, subagent.getStatus());
        
        System.out.println("✅ Timeout scenario test passed");
    }
    
    @Test
    void testCancellationScenario() {
        // 模拟取消场景
        
        AsyncSubagent subagent = AsyncSubagent.builder()
                .id("cancel-001")
                .name("CancelAgent")
                .mode(AsyncSubagentMode.WATCH)
                .status(AsyncSubagentStatus.RUNNING)
                .startTime(Instant.now())
                .prompt("Watch for pattern")
                .triggerPattern("SHUTDOWN")
                .build();
        
        // 模拟用户取消
        subagent.setStatus(AsyncSubagentStatus.CANCELLED);
        subagent.setEndTime(Instant.now());
        
        assertTrue(subagent.isCompleted());
        assertFalse(subagent.isSuccess());
        assertEquals(AsyncSubagentStatus.CANCELLED, subagent.getStatus());
        
        System.out.println("✅ Cancellation scenario test passed");
    }
}
