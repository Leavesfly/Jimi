package io.leavesfly.jimi.adk.api.wire;

/**
 * Wire 消息基类
 * 所有通过 Wire 传输的消息都应继承此类
 */
public abstract class WireMessage {
    
    /**
     * 消息时间戳
     */
    private final long timestamp;
    
    protected WireMessage() {
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * 获取消息时间戳
     *
     * @return 时间戳
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * 获取消息类型名称
     *
     * @return 消息类型名称
     */
    public String getMessageType() {
        return this.getClass().getSimpleName();
    }
}
