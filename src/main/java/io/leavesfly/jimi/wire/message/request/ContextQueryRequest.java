package io.leavesfly.jimi.wire.message.request;

import io.leavesfly.jimi.wire.message.WireRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

/**
 * 上下文查询请求
 * <p>
 * Client 通过此请求查询 Engine 的上下文状态（Token 数、历史消息数等）。
 */
@Getter
public class ContextQueryRequest extends WireRequest<ContextQueryRequest.ContextInfo> {

    @Override
    public String getMessageType() {
        return "request.context_query";
    }

    /**
     * 上下文信息响应
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class ContextInfo {
        private final int tokenCount;
        private final int historySize;
        private final int checkpointCount;
    }
}
