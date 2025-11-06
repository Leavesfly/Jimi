package io.leavesfly.jimi.tool.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP 协议本地Schema定义
 * 替代io.modelcontextprotocol.sdk中的McpSchema，提供轻量级本地实现
 * 支持MCP协议2024-11-05版本的核心功能
 */
public class MCPSchema {

    /**
     * MCP工具定义
     * 描述一个可调用的工具，包括名称、描述和参数Schema
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Tool {
        /** 工具名称，唯一标识 */
        @JsonProperty("name")
        private String name;
        
        /** 工具功能描述 */
        @JsonProperty("description")
        private String description;
        
        /** 输入参数Schema（JSON Schema格式） */
        @JsonProperty("inputSchema")
        private Map<String, Object> inputSchema;
    }

    /**
     * 工具列表查询结果
     * tools/list方法的返回结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListToolsResult {
        /** 服务提供的所有工具列表 */
        @JsonProperty("tools")
        private List<Tool> tools;
    }

    /**
     * 工具调用请求
     * 用于tools/call方法的参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallToolRequest {
        /** 要调用的工具名称 */
        @JsonProperty("name")
        private String name;
        
        /** 调用参数 */
        @JsonProperty("arguments")
        private Map<String, Object> arguments;
    }

    /**
     * 工具调用结果
     * tools/call方法的返回结果，包含多种类型的内容
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallToolResult {
        /** 返回内容列表（文本、图片、资源等） */
        @JsonProperty("content")
        private List<Content> content;
        
        /** 是否为错误结果 */
        @JsonProperty("isError")
        private Boolean isError;
    }

    /**
     * 内容接口
     * 所有内容类型的基础接口，包括文本、图片、嵌入资源等
     */
    public interface Content {}

    /**
     * 文本内容
     * 表示纯文本格式的输出结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TextContent implements Content {
        /** 内容类型标识 */
        @JsonProperty("type")
        private String type = "text";
        
        /** 文本内容 */
        @JsonProperty("text")
        private String text;
    }

    /**
     * 图片内容
     * 表示Base64编码的图片数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageContent implements Content {
        /** 内容类型标识 */
        @JsonProperty("type")
        private String type = "image";
        
        /** Base64编码的图片数据 */
        @JsonProperty("data")
        private String data;
        
        /** MIME类型，如image/png、image/jpeg */
        @JsonProperty("mimeType")
        private String mimeType;
    }

    /**
     * 嵌入资源
     * 表示嵌入在响应中的外部资源
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmbeddedResource implements Content {
        /** 内容类型标识 */
        @JsonProperty("type")
        private String type = "resource";
        
        /** 资源内容 */
        @JsonProperty("resource")
        private ResourceContents resource;
    }

    /**
     * 资源内容
     * 描述嵌入资源的具体内容
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceContents {
        /** 资源URI标识 */
        @JsonProperty("uri")
        private String uri;
        
        /** MIME类型 */
        @JsonProperty("mimeType")
        private String mimeType;
        
        /** 二进制数据（Base64编码） */
        @JsonProperty("blob")
        private String blob;
    }

    /**
     * 初始化请求
     * 用于initialize方法，建立客户端与服务端的连接
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InitializeRequest {
        /** MCP协议版本 */
        @JsonProperty("protocolVersion")
        private String protocolVersion = "2024-11-05";
        
        /** 客户端能力声明 */
        @JsonProperty("capabilities")
        private Map<String, Object> capabilities;
        
        /** 客户端信息 */
        @JsonProperty("clientInfo")
        private ClientInfo clientInfo;
    }

    /**
     * 客户端信息
     * 描述连接的客户端名称和版本
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClientInfo {
        /** 客户端名称 */
        @JsonProperty("name")
        private String name = "jimi";
        
        /** 客户端版本 */
        @JsonProperty("version")
        private String version = "0.1.0";
    }

    /**
     * 初始化响应
     * 服务端返回的初始化结果，包含服务端信息和能力
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InitializeResult {
        /** 服务端支持的协议版本 */
        @JsonProperty("protocolVersion")
        private String protocolVersion;
        
        /** 服务端能力声明 */
        @JsonProperty("capabilities")
        private Map<String, Object> capabilities;
        
        /** 服务端信息 */
        @JsonProperty("serverInfo")
        private Map<String, Object> serverInfo;
    }
}
