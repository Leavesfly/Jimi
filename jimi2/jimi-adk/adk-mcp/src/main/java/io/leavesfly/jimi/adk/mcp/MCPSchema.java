package io.leavesfly.jimi.adk.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP 协议本地 Schema 定义
 * 支持 MCP 协议 2024-11-05 版本的核心功能
 */
public class MCPSchema {

    /**
     * MCP 工具定义
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Tool {
        @JsonProperty("name")
        private String name;

        @JsonProperty("description")
        private String description;

        @JsonProperty("inputSchema")
        private Map<String, Object> inputSchema;
    }

    /**
     * 工具列表查询结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListToolsResult {
        @JsonProperty("tools")
        private List<Tool> tools;
    }

    /**
     * 工具调用请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallToolRequest {
        @JsonProperty("name")
        private String name;

        @JsonProperty("arguments")
        private Map<String, Object> arguments;
    }

    /**
     * 工具调用结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallToolResult {
        @JsonProperty("content")
        private List<Content> content;

        @JsonProperty("isError")
        private Boolean isError;
    }

    /**
     * 内容接口
     */
    public interface Content {}

    /**
     * 文本内容
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TextContent implements Content {
        @JsonProperty("type")
        @Builder.Default
        private String type = "text";

        @JsonProperty("text")
        private String text;
    }

    /**
     * 图片内容
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageContent implements Content {
        @JsonProperty("type")
        @Builder.Default
        private String type = "image";

        @JsonProperty("data")
        private String data;

        @JsonProperty("mimeType")
        private String mimeType;
    }

    /**
     * 嵌入资源
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmbeddedResource implements Content {
        @JsonProperty("type")
        @Builder.Default
        private String type = "resource";

        @JsonProperty("resource")
        private ResourceContents resource;
    }

    /**
     * 资源内容
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceContents {
        @JsonProperty("uri")
        private String uri;

        @JsonProperty("mimeType")
        private String mimeType;

        @JsonProperty("blob")
        private String blob;
    }

    /**
     * 初始化请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InitializeRequest {
        @JsonProperty("protocolVersion")
        @Builder.Default
        private String protocolVersion = "2024-11-05";

        @JsonProperty("capabilities")
        private Map<String, Object> capabilities;

        @JsonProperty("clientInfo")
        private ClientInfo clientInfo;
    }

    /**
     * 客户端信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClientInfo {
        @JsonProperty("name")
        @Builder.Default
        private String name = "jimi2";

        @JsonProperty("version")
        @Builder.Default
        private String version = "0.1.0";
    }

    /**
     * 初始化响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InitializeResult {
        @JsonProperty("protocolVersion")
        private String protocolVersion;

        @JsonProperty("capabilities")
        private Map<String, Object> capabilities;

        @JsonProperty("serverInfo")
        private Map<String, Object> serverInfo;
    }
}
