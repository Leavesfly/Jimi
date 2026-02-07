package io.leavesfly.jimi.adk.core.wire.messages;

import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.api.wire.WireMessage;
import lombok.Getter;

/**
 * 工具执行结果消息
 */
@Getter
public class ToolResultMessage extends WireMessage {
    
    /**
     * 工具调用 ID
     */
    private final String toolCallId;
    
    /**
     * 工具名称
     */
    private final String toolName;
    
    /**
     * 工具执行结果
     */
    private final ToolResult toolResult;
    
    public ToolResultMessage(String toolCallId, String toolName, ToolResult toolResult) {
        super();
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolResult = toolResult;
    }
}
