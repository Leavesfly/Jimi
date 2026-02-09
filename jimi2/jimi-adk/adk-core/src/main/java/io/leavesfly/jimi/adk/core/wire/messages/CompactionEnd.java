package io.leavesfly.jimi.adk.core.wire.messages;

import io.leavesfly.jimi.adk.api.wire.WireMessage;
import lombok.Getter;

/**
 * 上下文压缩结束消息
 */
@Getter
public class CompactionEnd extends WireMessage {
    
    /**
     * 压缩前的消息数
     */
    private final int beforeCount;
    
    /**
     * 压缩后的消息数
     */
    private final int afterCount;
    
    /**
     * 是否压缩成功
     */
    private final boolean success;
    
    public CompactionEnd(int beforeCount, int afterCount, boolean success) {
        super();
        this.beforeCount = beforeCount;
        this.afterCount = afterCount;
        this.success = success;
    }
    
    public CompactionEnd() {
        this(0, 0, true);
    }
}
