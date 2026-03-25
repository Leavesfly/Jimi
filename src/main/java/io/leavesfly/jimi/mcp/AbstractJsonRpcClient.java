package io.leavesfly.jimi.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-RPC 客户端抽象基类
 * 封装 MCP 协议的公共逻辑（initialize、listTools、callTool），
 * 子类只需实现底层的 sendRequest 传输方式（STDIO 或 HTTP）
 */
@Slf4j
public abstract class AbstractJsonRpcClient implements JsonRpcClient {

    protected final ObjectMapper objectMapper;

    protected AbstractJsonRpcClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    protected AbstractJsonRpcClient() {
        this(new ObjectMapper());
    }

    /**
     * 发送 JSON-RPC 请求并等待响应
     * 由子类实现具体的传输方式
     *
     * @param method JSON-RPC 方法名
     * @param params 方法参数
     * @return 服务端返回的响应
     * @throws Exception 请求失败或超时时抛出
     */
    protected abstract JsonRpcMessage.Response sendRequest(String method, Map<String, Object> params) throws Exception;

    @Override
    public MCPSchema.InitializeResult initialize() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "jimi", "version", "0.1.0"));

        JsonRpcMessage.Response response = sendRequest("initialize", params);

        if (response.getError() != null) {
            throw new RuntimeException("Initialize failed: " + response.getError().getMessage());
        }

        return objectMapper.convertValue(response.getResult(), MCPSchema.InitializeResult.class);
    }

    @Override
    public MCPSchema.ListToolsResult listTools() throws Exception {
        JsonRpcMessage.Response response = sendRequest("tools/list", Map.of());

        if (response.getError() != null) {
            throw new RuntimeException("List tools failed: " + response.getError().getMessage());
        }

        return objectMapper.convertValue(response.getResult(), MCPSchema.ListToolsResult.class);
    }

    @Override
    public MCPSchema.CallToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : Map.of());

        JsonRpcMessage.Response response = sendRequest("tools/call", params);

        if (response.getError() != null) {
            throw new RuntimeException("Call tool failed: " + response.getError().getMessage());
        }

        Map<String, Object> result = response.getResult();
        List<MCPSchema.Content> contents = parseContents(result.get("content"));

        return MCPSchema.CallToolResult.builder()
                .content(contents)
                .isError((Boolean) result.get("isError"))
                .build();
    }

    /**
     * 解析 content 字段为 Content 对象列表
     * 将 JSON 数据转换为具体的 Content 子类实例
     */
    @SuppressWarnings("unchecked")
    private static List<MCPSchema.Content> parseContents(Object contentObj) {
        List<MCPSchema.Content> contents = new ArrayList<>();

        if (!(contentObj instanceof List)) {
            return contents;
        }

        List<Map<String, Object>> contentList = (List<Map<String, Object>>) contentObj;

        for (Map<String, Object> item : contentList) {
            String type = (String) item.get("type");

            if ("text".equals(type)) {
                contents.add(MCPSchema.TextContent.builder()
                        .type("text")
                        .text((String) item.get("text"))
                        .build());
            } else if ("image".equals(type)) {
                contents.add(MCPSchema.ImageContent.builder()
                        .type("image")
                        .data((String) item.get("data"))
                        .mimeType((String) item.get("mimeType"))
                        .build());
            } else if ("resource".equals(type)) {
                Map<String, Object> resource = (Map<String, Object>) item.get("resource");
                if (resource != null) {
                    contents.add(MCPSchema.EmbeddedResource.builder()
                            .type("resource")
                            .resource(MCPSchema.ResourceContents.builder()
                                    .uri((String) resource.get("uri"))
                                    .mimeType((String) resource.get("mimeType"))
                                    .blob((String) resource.get("blob"))
                                    .build())
                            .build());
                }
            }
        }

        return contents;
    }
}
