package io.leavesfly.jimi.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP内容解析工具类
 * 负责将JSON数据解析为MCP Content对象
 * 
 * @author Jimi Team
 */
public class MCPContentParser {
    
    /**
     * 解析content字段为Content对象列表
     * 将JSON数据转换为具体的Content子类实例
     * 
     * @param contentObj 原始content数据
     * @return 解析后的Content列表
     */
    @SuppressWarnings("unchecked")
    public static List<MCPSchema.Content> parseContents(Object contentObj) {
        List<MCPSchema.Content> contents = new ArrayList<>();
        
        if (!(contentObj instanceof List)) {
            return contents;
        }
        
        List<Map<String, Object>> contentList = (List<Map<String, Object>>) contentObj;
        
        // 遍历每个内容项，根据type字段转换为对应类型
        for (Map<String, Object> item : contentList) {
            String type = (String) item.get("type");
            
            if ("text".equals(type)) {
                // 文本内容
                contents.add(MCPSchema.TextContent.builder()
                        .type("text")
                        .text((String) item.get("text"))
                        .build());
            } else if ("image".equals(type)) {
                // 图片内容
                contents.add(MCPSchema.ImageContent.builder()
                        .type("image")
                        .data((String) item.get("data"))
                        .mimeType((String) item.get("mimeType"))
                        .build());
            } else if ("resource".equals(type)) {
                // 嵌入资源
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
