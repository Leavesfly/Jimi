package io.leavesfly.jimi.cli.notification;

/**
 * 通知类型枚举
 */
public enum NotificationType {
    
    /**
     * 成功通知
     */
    SUCCESS("success", "✅"),
    
    /**
     * 错误通知
     */
    ERROR("error", "❌"),
    
    /**
     * 警告通知
     */
    WARNING("warning", "⚠️"),
    
    /**
     * 信息通知
     */
    INFO("info", "ℹ️"),
    
    /**
     * 监控触发通知
     */
    TRIGGER("trigger", "🔔");
    
    private final String value;
    private final String icon;
    
    NotificationType(String value, String icon) {
        this.value = value;
        this.icon = icon;
    }
    
    public String getValue() {
        return value;
    }
    
    public String getIcon() {
        return icon;
    }
}
