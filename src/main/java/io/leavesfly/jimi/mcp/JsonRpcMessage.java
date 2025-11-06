package io.leavesfly.jimi.tool.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * JSON-RPC 2.0 消息定义
 * 用于MCP协议的标准JSON-RPC通信，包含请求、响应和错误三种消息类型
 */
public class JsonRpcMessage {

    /**
     * JSON-RPC 请求消息
     * 用于向MCP服务发送调用请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Request {
        /** JSON-RPC协议版本，固定为"2.0" */
        @JsonProperty("jsonrpc")
        private String jsonrpc = "2.0";
        
        /** 请求ID，用于匹配响应 */
        @JsonProperty("id")
        private Object id;
        
        /** 调用方法名，如"initialize"、"tools/list"、"tools/call" */
        @JsonProperty("method")
        private String method;
        
        /** 方法参数 */
        @JsonProperty("params")
        private Map<String, Object> params;
    }

    /**
     * JSON-RPC 响应消息
     * MCP服务返回的调用结果或错误信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        /** JSON-RPC协议版本 */
        @JsonProperty("jsonrpc")
        private String jsonrpc = "2.0";
        
        /** 响应ID，与请求ID对应 */
        @JsonProperty("id")
        private Object id;
        
        /** 成功时返回的结果数据 */
        @JsonProperty("result")
        private Map<String, Object> result;
        
        /** 失败时返回的错误信息 */
        @JsonProperty("error")
        private Error error;
    }

    /**
     * JSON-RPC 错误对象
     * 包含错误码、错误信息和附加数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Error {
        /** 错误码 */
        @JsonProperty("code")
        private int code;
        
        /** 错误描述信息 */
        @JsonProperty("message")
        private String message;
        
        /** 附加错误数据（可选） */
        @JsonProperty("data")
        private Object data;
    }
}
