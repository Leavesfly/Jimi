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
     * 包含类型安全检查以防止 ClassCastException
     */
    @SuppressWarnings("unchecked")
    private List<MCPSchema.Content> parseContents(Object contentObj) {
        List<MCPSchema.Content> contents = new ArrayList<>();

        if (!(contentObj instanceof List)) {
            return contents;
        }

        List<?> contentList = (List<?>) contentObj;

        for (Object element : contentList) {
            // 类型安全检查：确保每个元素是 Map
            if (!(element instanceof Map)) {
                log.warn("Skipping non-Map element in content list: {}",
                        element != null ? element.getClass().getName() : "null");
                continue;
            }

            Map<String, Object> item = (Map<String, Object>) element;
            Object typeObj = item.get("type");
            // 类型安全检查：确保 type 字段是 String
            if (!(typeObj instanceof String)) {
                log.warn("Skipping content item with invalid type field: {}",
                        typeObj != null ? typeObj.getClass().getName() : "null");
                continue;
            }
            String type = (String) typeObj;

            if ("text".equals(type)) {
                Object textObj = item.get("text");
                String text = (textObj instanceof String) ? (String) textObj : null;
                contents.add(MCPSchema.TextContent.builder()
                        .type("text")
                        .text(text)
                        .build());
            } else if ("image".equals(type)) {
                Object dataObj = item.get("data");
                Object mimeTypeObj = item.get("mimeType");
                contents.add(MCPSchema.ImageContent.builder()
                        .type("image")
                        .data((dataObj instanceof String) ? (String) dataObj : null)
                        .mimeType((mimeTypeObj instanceof String) ? (String) mimeTypeObj : null)
                        .build());
            } else if ("resource".equals(type)) {
                Object resourceObj = item.get("resource");
                // 类型安全检查：确保 resource 字段是 Map
                if (resourceObj instanceof Map) {
                    Map<String, Object> resource = (Map<String, Object>) resourceObj;
                    Object uriObj = resource.get("uri");
                    Object resMimeTypeObj = resource.get("mimeType");
                    Object blobObj = resource.get("blob");
                    contents.add(MCPSchema.EmbeddedResource.builder()
                            .type("resource")
                            .resource(MCPSchema.ResourceContents.builder()
                                    .uri((uriObj instanceof String) ? (String) uriObj : null)
                                    .mimeType((resMimeTypeObj instanceof String) ? (String) resMimeTypeObj : null)
                                    .blob((blobObj instanceof String) ? (String) blobObj : null)
                                    .build())
                            .build());
                } else if (resourceObj != null) {
                    log.warn("Skipping resource content with invalid resource field: {}",
                            resourceObj.getClass().getName());
                }
            }
        }

        return contents;
    }
}
