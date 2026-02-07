package io.leavesfly.jimi.adk.core.wire.messages;

import io.leavesfly.jimi.adk.api.message.ToolCall;
import io.leavesfly.jimi.adk.api.wire.WireMessage;
import lombok.Getter;

/**
 * 工具调用消息
 */
@Getter
public class ToolCallMessage extends WireMessage {
    
    /**
     * 工具调用信息
     */
    private final ToolCall toolCall;
    
    public ToolCallMessage(ToolCall toolCall) {
        super();
        this.toolCall = toolCall;
    }
}
