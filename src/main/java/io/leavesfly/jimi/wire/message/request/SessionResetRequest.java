package io.leavesfly.jimi.wire.message.request;

import io.leavesfly.jimi.wire.message.WireRequest;

/**
 * 会话重置请求
 * <p>
 * Client 通过此请求让 Engine 重置上下文（清空历史消息，回退到初始状态）。
 */
public class SessionResetRequest extends WireRequest<Void> {

    @Override
    public String getMessageType() {
        return "request.session_reset";
    }
}
