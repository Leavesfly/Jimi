package io.leavesfly.jimi.adk.core.wire.messages;

import io.leavesfly.jimi.adk.api.wire.WireMessage;
import lombok.Getter;

/**
 * 状态更新消息
 * 用于向 UI 层通知执行状态变化
 */
@Getter
public class StatusUpdate extends WireMessage {
    
    /**
     * 状态类型
     */
    private final StatusType type;
    
    /**
     * 状态消息
     */
    private final String message;
    
    public StatusUpdate(StatusType type, String message) {
        super();
        this.type = type;
        this.message = message;
    }
    
    /**
     * 状态类型枚举
     */
    public enum StatusType {
        /** 信息提示 */
        INFO,
        /** 警告 */
        WARNING,
        /** 错误 */
        ERROR,
        /** 进度更新 */
        PROGRESS,
        /** 完成 */
        COMPLETE
    }
    
    /**
     * 创建信息消息
     */
    public static StatusUpdate info(String message) {
        return new StatusUpdate(StatusType.INFO, message);
    }
    
    /**
     * 创建警告消息
     */
    public static StatusUpdate warning(String message) {
        return new StatusUpdate(StatusType.WARNING, message);
    }
    
    /**
     * 创建错误消息
     */
    public static StatusUpdate error(String message) {
        return new StatusUpdate(StatusType.ERROR, message);
    }
    
    /**
     * 创建进度消息
     */
    public static StatusUpdate progress(String message) {
        return new StatusUpdate(StatusType.PROGRESS, message);
    }
}
