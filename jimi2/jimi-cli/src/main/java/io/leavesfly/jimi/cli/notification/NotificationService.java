package io.leavesfly.jimi.cli.notification;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 系统通知服务
 * 提供桌面通知、终端提示音等多种通知方式
 */
@Slf4j
public class NotificationService {
    
    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();
    private static final boolean IS_MAC = OS_NAME.contains("mac");
    private static final boolean IS_LINUX = OS_NAME.contains("linux");
    private static final boolean IS_WINDOWS = OS_NAME.contains("windows");
    
    private boolean desktopNotificationEnabled;
    
    public NotificationService() {
        this.desktopNotificationEnabled = true;
    }
    
    public NotificationService(boolean desktopNotificationEnabled) {
        this.desktopNotificationEnabled = desktopNotificationEnabled;
    }
    
    /**
     * 设置是否启用桌面通知
     */
    public void setDesktopNotificationEnabled(boolean enabled) {
        this.desktopNotificationEnabled = enabled;
    }
    
    /**
     * 发送通知
     * 
     * @param title 通知标题
     * @param message 通知内容
     * @param type 通知类型
     */
    public void notify(String title, String message, NotificationType type) {
        if (desktopNotificationEnabled) {
            sendDesktopNotification(title, message, type);
        }
    }
    
    /**
     * 发送桌面通知
     */
    private void sendDesktopNotification(String title, String message, NotificationType type) {
        try {
            if (IS_MAC) {
                sendMacNotification(title, message, type);
            } else if (IS_LINUX) {
                sendLinuxNotification(title, message, type);
            } else if (IS_WINDOWS) {
                sendWindowsNotification(title, message, type);
            } else {
                log.debug("Desktop notification not supported on this platform: {}", OS_NAME);
            }
        } catch (Exception e) {
            log.debug("Failed to send desktop notification: {}", e.getMessage());
        }
    }
    
    /**
     * macOS 通知（使用 osascript）
     */
    private void sendMacNotification(String title, String message, NotificationType type) {
        try {
            String icon = type.getIcon();
            String fullTitle = icon + " Jimi: " + title;
            
            String script = String.format(
                "display notification \"%s\" with title \"%s\" sound name \"default\"",
                escapeAppleScript(message),
                escapeAppleScript(fullTitle)
            );
            
            ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("macOS notification timed out");
            } else if (process.exitValue() != 0) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String error = reader.lines().reduce("", (a, b) -> a + b);
                    log.debug("macOS notification failed: {}", error);
                }
            } else {
                log.debug("macOS notification sent: {}", title);
            }
        } catch (Exception e) {
            log.debug("Failed to send macOS notification: {}", e.getMessage());
        }
    }
    
    /**
     * Linux 通知（使用 notify-send）
     */
    private void sendLinuxNotification(String title, String message, NotificationType type) {
        try {
            String icon = type.getIcon();
            String fullTitle = icon + " Jimi: " + title;
            
            String urgency = switch (type) {
                case ERROR -> "critical";
                case WARNING, TRIGGER -> "normal";
                default -> "low";
            };
            
            ProcessBuilder pb = new ProcessBuilder(
                "notify-send",
                "-u", urgency,
                "-a", "Jimi",
                fullTitle,
                message
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            
            log.debug("Linux notification sent: {}", title);
        } catch (Exception e) {
            log.debug("Failed to send Linux notification (notify-send may not be installed): {}", 
                    e.getMessage());
        }
    }
    
    /**
     * Windows 通知（使用 PowerShell）
     */
    private void sendWindowsNotification(String title, String message, NotificationType type) {
        try {
            String icon = type.getIcon();
            String fullTitle = icon + " Jimi: " + title;
            
            String script = String.format(
                "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null; " +
                "$template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02); " +
                "$textNodes = $template.GetElementsByTagName('text'); " +
                "$textNodes.Item(0).AppendChild($template.CreateTextNode('%s')) | Out-Null; " +
                "$textNodes.Item(1).AppendChild($template.CreateTextNode('%s')) | Out-Null; " +
                "$toast = [Windows.UI.Notifications.ToastNotification]::new($template); " +
                "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('Jimi').Show($toast)",
                escapeForPowerShell(fullTitle),
                escapeForPowerShell(message)
            );
            
            ProcessBuilder pb = new ProcessBuilder("powershell", "-Command", script);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            
            log.debug("Windows notification sent: {}", title);
        } catch (Exception e) {
            log.debug("Failed to send Windows notification: {}", e.getMessage());
        }
    }
    
    /**
     * 转义 AppleScript 字符串
     */
    private String escapeAppleScript(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", " ");
    }
    
    /**
     * 转义 PowerShell 字符串
     */
    private String escapeForPowerShell(String text) {
        if (text == null) return "";
        return text.replace("'", "''")
                   .replace("\"", "`\"")
                   .replace("\n", " ");
    }
    
    /**
     * 快捷方法：发送异步子代理完成通知
     */
    public void notifyAsyncComplete(String subagentId, String result, boolean success) {
        String title = success ? "任务完成" : "任务失败";
        String message = String.format("[%s] %s", 
                subagentId, 
                result != null && result.length() > 100 ? result.substring(0, 100) + "..." : result);
        
        notify(title, message, success ? NotificationType.SUCCESS : NotificationType.ERROR);
    }
    
    /**
     * 快捷方法：发送监控触发通知
     */
    public void notifyWatchTrigger(String subagentId, String matchedPattern, String content) {
        String title = "监控触发";
        String message = String.format("[%s] 匹配: %s\n%s", 
                subagentId, 
                matchedPattern,
                content != null && content.length() > 80 ? content.substring(0, 80) + "..." : content);
        
        notify(title, message, NotificationType.TRIGGER);
    }
}
