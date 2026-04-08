package io.leavesfly.jimi.wire.message.request;

import io.leavesfly.jimi.wire.message.WireRequest;

import java.util.List;

/**
 * 工具名称列表查询请求
 * <p>
 * Client 通过此请求获取 Engine 中所有已注册工具的名称列表。
 */
public class ToolNamesQueryRequest extends WireRequest<List<String>> {

    @Override
    public String getMessageType() {
        return "request.tool_names_query";
    }
}
