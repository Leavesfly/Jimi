package io.leavesfly.jimi.wire.message.request;

import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.wire.message.WireRequest;
import lombok.Getter;

/**
 * 工具执行请求
 * <p>
 * Client 通过此请求让 Engine 执行指定工具。
 */
@Getter
public class ToolExecuteRequest extends WireRequest<ToolResult> {

    private final String toolName;
    private final String arguments;

    public ToolExecuteRequest(String toolName, String arguments) {
        this.toolName = toolName;
        this.arguments = arguments;
    }

    @Override
    public String getMessageType() {
        return "request.tool_execute";
    }
}
