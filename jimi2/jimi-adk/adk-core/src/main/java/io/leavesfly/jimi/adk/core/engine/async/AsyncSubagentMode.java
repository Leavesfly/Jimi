package io.leavesfly.jimi.adk.core.engine.async;

/**
 * 异步子代理运行模式枚举
 */
public enum AsyncSubagentMode {
    
    /**
     * 启动后不等待，后台执行
     * 主代理立即收到返回，子代理在后台独立运行
     */
    FIRE_AND_FORGET("fire_and_forget", "后台运行"),
    
    /**
     * 持续监控模式
     * 子代理持续运行，直到匹配特定模式或被取消
     */
    WATCH("watch", "持续监控"),
    
    /**
     * 等待完成模式
     * 兼容同步模式，等待子代理完成后返回结果
     */
    WAIT_COMPLETE("wait_complete", "等待完成");
    
    private final String value;
    private final String displayName;
    
    AsyncSubagentMode(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }
    
    public String getValue() {
        return value;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * 从字符串值解析模式
     */
    public static AsyncSubagentMode fromValue(String value) {
        if (value == null) {
            return FIRE_AND_FORGET;
        }
        for (AsyncSubagentMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return FIRE_AND_FORGET;
    }
}
