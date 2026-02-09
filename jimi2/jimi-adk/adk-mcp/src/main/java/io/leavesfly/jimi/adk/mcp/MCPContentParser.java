package io.leavesfly.jimi.adk.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 内容解析工具类
 * 负责将 JSON 数据解析为 MCP Content 对象
 */
public class MCPContentParser {

    /**
     * 解析 content 字段为 Content 对象列表
     *
     * @param contentObj 原始 content 数据
     * @return 解析后的 Content 列表
     */
    @SuppressWarnings("unchecked")
    public static List<MCPSchema.Content> parseContents(Object contentObj) {
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
