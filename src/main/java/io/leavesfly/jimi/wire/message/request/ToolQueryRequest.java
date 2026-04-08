package io.leavesfly.jimi.wire.message.request;

import io.leavesfly.jimi.wire.message.WireRequest;
import lombok.Getter;

/**
 * 工具查询请求
 * <p>
 * Client 通过此请求查询 Engine 是否拥有指定工具。
 */
@Getter
public class ToolQueryRequest extends WireRequest<Boolean> {

    private final String toolName;

    public ToolQueryRequest(String toolName) {
        this.toolName = toolName;
    }

    @Override
    public String getMessageType() {
        return "request.tool_query";
    }
}
