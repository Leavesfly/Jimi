package io.leavesfly.jimi.adk.core.engine.async;

/**
 * 异步子代理状态枚举
 */
public enum AsyncSubagentStatus {
    
    /** 等待启动 */
    PENDING("pending", "等待中"),
    
    /** 运行中 */
    RUNNING("running", "运行中"),
    
    /** 正常完成 */
    COMPLETED("completed", "已完成"),
    
    /** 执行失败 */
    FAILED("failed", "已失败"),
    
    /** 被取消 */
    CANCELLED("cancelled", "已取消"),
    
    /** 超时 */
    TIMEOUT("timeout", "已超时");
    
    private final String value;
    private final String displayName;
    
    AsyncSubagentStatus(String value, String displayName) {
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
     * 是否为终态
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMEOUT;
    }
}
