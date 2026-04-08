package io.leavesfly.jimi.wire.message.request;

import io.leavesfly.jimi.wire.message.WireRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 运行时信息查询请求
 * <p>
 * Client 通过此请求查询 Engine 的运行时配置信息（LLM 状态、会话信息、工作目录等）。
 */
public class RuntimeInfoQueryRequest extends WireRequest<RuntimeInfoQueryRequest.RuntimeInfo> {

    @Override
    public String getMessageType() {
        return "request.runtime_info_query";
    }

    /**
     * 运行时信息响应
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class RuntimeInfo {
        /** LLM 是否已配置 */
        private final boolean llmConfigured;
        /** 工作目录路径 */
        private final String workDir;
        /** 会话 ID */
        private final String sessionId;
        /** 历史文件路径 */
        private final String historyFile;
        /** 是否为 YOLO 模式 */
        private final boolean yoloMode;
    }
}
