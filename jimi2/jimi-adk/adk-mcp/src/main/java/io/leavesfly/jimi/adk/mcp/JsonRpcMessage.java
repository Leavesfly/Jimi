package io.leavesfly.jimi.adk.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * JSON-RPC 2.0 消息定义
 * 用于 MCP 协议的标准 JSON-RPC 通信
 */
public class JsonRpcMessage {

    /**
     * JSON-RPC 请求消息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Request {
        @JsonProperty("jsonrpc")
        @Builder.Default
        private String jsonrpc = "2.0";

        @JsonProperty("id")
        private Object id;

        @JsonProperty("method")
        private String method;

        @JsonProperty("params")
        private Map<String, Object> params;
    }

    /**
     * JSON-RPC 响应消息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        @JsonProperty("jsonrpc")
        @Builder.Default
        private String jsonrpc = "2.0";

        @JsonProperty("id")
        private Object id;

        @JsonProperty("result")
        private Map<String, Object> result;

        @JsonProperty("error")
        private Error error;
    }

    /**
     * JSON-RPC 错误对象
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Error {
        @JsonProperty("code")
        private int code;

        @JsonProperty("message")
        private String message;

        @JsonProperty("data")
        private Object data;
    }
}
