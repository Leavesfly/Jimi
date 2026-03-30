package io.leavesfly.jimi.ui.notification;

import io.leavesfly.jimi.config.info.ShellUIConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通知服务测试
 */
class NotificationServiceTest {
    
    private NotificationService notificationService;
    private ShellUIConfig enabledConfig;
    private ShellUIConfig disabledConfig;
    
    @BeforeEach
    void setUp() {
        notificationService = new NotificationService();
        
        // 启用通知的配置
        enabledConfig = new ShellUIConfig();
        enabledConfig.setEnableDesktopNotification(true);
        enabledConfig.setEnableNotificationSound(true);
        
        // 禁用通知的配置
        disabledConfig = new ShellUIConfig();
        disabledConfig.setEnableDesktopNotification(false);
        disabledConfig.setEnableNotificationSound(false);
    }
    
    @Test
    void testNotifyWithNullConfig() {
        // 不应该抛出异常
        assertDoesNotThrow(() -> {
            notificationService.notify("Test Title", "Test Message", NotificationType.INFO, null);
        });
        
        System.out.println("✅ Notify with null config test passed");
    }
    
    @Test
    void testNotifyWithDisabledConfig() {
        // 不应该抛出异常
        assertDoesNotThrow(() -> {
            notificationService.notify("Test Title", "Test Message", NotificationType.INFO, disabledConfig);
        });
        
        System.out.println("✅ Notify with disabled config test passed");
    }
    
    @Test
    void testNotifyWithEnabledConfig() {
        // 在 CI 环境中可能无法发送实际通知，但不应该抛出异常
        assertDoesNotThrow(() -> {
            notificationService.notify("Test Title", "Test Message", NotificationType.SUCCESS, enabledConfig);
        });
        
        System.out.println("✅ Notify with enabled config test passed");
    }
    
    @Test
    void testAllNotificationTypes() {
        for (NotificationType type : NotificationType.values()) {
            assertDoesNotThrow(() -> {
                notificationService.notify("Type Test", "Testing " + type.name(), type, enabledConfig);
            }, "Should not throw for type: " + type.name());
        }
        
        System.out.println("✅ All notification types test passed: " + NotificationType.values().length + " types tested");
    }
    
    @Test
    void testNotifyAsyncComplete() {
        // 测试成功完成
        assertDoesNotThrow(() -> {
            notificationService.notifyAsyncComplete("abc123", "SubAgentTool completed successfully", true, enabledConfig);
        });
        
        // 测试失败完成
        assertDoesNotThrow(() -> {
            notificationService.notifyAsyncComplete("def456", "SubAgentTool failed with error", false, enabledConfig);
        });
        
        // 测试长结果截断
        String longResult = "A".repeat(200);
        assertDoesNotThrow(() -> {
            notificationService.notifyAsyncComplete("ghi789", longResult, true, enabledConfig);
        });
        
        // 测试 null 结果
        assertDoesNotThrow(() -> {
            notificationService.notifyAsyncComplete("jkl012", null, true, enabledConfig);
        });
        
        System.out.println("✅ Notify async complete test passed");
    }
    
    @Test
    void testNotifyWatchTrigger() {
        // 正常触发
        assertDoesNotThrow(() -> {
            notificationService.notifyWatchTrigger("watch-001", "ERROR.*", "ERROR: NullPointerException", enabledConfig);
        });
        
        // 长内容截断
        String longContent = "B".repeat(200);
        assertDoesNotThrow(() -> {
            notificationService.notifyWatchTrigger("watch-002", "pattern", longContent, enabledConfig);
        });
        
        // null 内容
        assertDoesNotThrow(() -> {
            notificationService.notifyWatchTrigger("watch-003", "pattern", null, enabledConfig);
        });
        
        System.out.println("✅ Notify watch trigger test passed");
    }
    
    @Test
    void testNotificationTypeValues() {
        assertEquals("success", NotificationType.SUCCESS.getValue());
        assertEquals("error", NotificationType.ERROR.getValue());
        assertEquals("warning", NotificationType.WARNING.getValue());
        assertEquals("info", NotificationType.INFO.getValue());
        assertEquals("trigger", NotificationType.TRIGGER.getValue());
        
        System.out.println("✅ Notification type values test passed");
    }
    
    @Test
    void testNotificationTypeIcons() {
        assertEquals("✅", NotificationType.SUCCESS.getIcon());
        assertEquals("❌", NotificationType.ERROR.getIcon());
        assertEquals("⚠️", NotificationType.WARNING.getIcon());
        assertEquals("ℹ️", NotificationType.INFO.getIcon());
        assertEquals("🔔", NotificationType.TRIGGER.getIcon());
        
        System.out.println("✅ Notification type icons test passed");
    }
    
    @Test
    void testSpecialCharactersInMessage() {
        // 测试包含特殊字符的消息
        assertDoesNotThrow(() -> {
            notificationService.notify(
                "Special \"Title\" with 'quotes'",
                "Message with\nnewline and\ttab and \"quotes\"",
                NotificationType.INFO,
                enabledConfig
            );
        });
        
        // 测试包含反斜杠
        assertDoesNotThrow(() -> {
            notificationService.notify(
                "Path: C:\\Users\\Test",
                "File: /path/to/file.txt",
                NotificationType.INFO,
                enabledConfig
            );
        });
        
        // 测试包含 Unicode
        assertDoesNotThrow(() -> {
            notificationService.notify(
                "中文标题 🎉",
                "中文内容 with emoji 🚀",
                NotificationType.SUCCESS,
                enabledConfig
            );
        });
        
        System.out.println("✅ Special characters in message test passed");
    }
    
    @Test
    void testEmptyStrings() {
        assertDoesNotThrow(() -> {
            notificationService.notify("", "", NotificationType.INFO, enabledConfig);
        });
        
        assertDoesNotThrow(() -> {
            notificationService.notifyAsyncComplete("id", "", true, enabledConfig);
        });
        
        assertDoesNotThrow(() -> {
            notificationService.notifyWatchTrigger("id", "", "", enabledConfig);
        });
        
        System.out.println("✅ Empty strings test passed");
    }
    
    @Test
    void testNullConfig() {
        // 所有方法对 null config 的处理
        assertDoesNotThrow(() -> {
            notificationService.notify("Title", "Message", NotificationType.INFO, null);
            notificationService.notifyAsyncComplete("id", "result", true, null);
            notificationService.notifyWatchTrigger("id", "pattern", "content", null);
        });
        
        System.out.println("✅ Null config handling test passed");
    }
}
