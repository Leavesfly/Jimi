package io.leavesfly.jimi.adk.mcp;

import io.leavesfly.jimi.adk.api.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * MCP 结果转换器
 * 将 MCP 工具调用结果转换为 Jimi2 的 ToolResult 格式
 *
 * 处理多种内容类型：文本、图片、嵌入资源
 */
@Slf4j
public class MCPResultConverter {

    /**
     * 转换 MCP 调用结果为 ToolResult
     *
     * @param mcpResult MCP 工具调用结果
     * @return ToolResult 对象
     */
    public static ToolResult convert(MCPSchema.CallToolResult mcpResult) {
        if (mcpResult == null) {
            return ToolResult.error("MCP result is null");
        }

        // 检查是否为错误结果
        if (Boolean.TRUE.equals(mcpResult.getIsError())) {
            String errorText = extractText(mcpResult.getContent());
            return ToolResult.error(errorText.isEmpty() ? "MCP tool returned error" : errorText);
        }

        List<MCPSchema.Content> contents = mcpResult.getContent();
        if (contents == null || contents.isEmpty()) {
            return ToolResult.success("");
        }

        // 拼接所有文本内容
        StringBuilder sb = new StringBuilder();
        for (MCPSchema.Content content : contents) {
            if (content instanceof MCPSchema.TextContent textContent) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(textContent.getText());
            } else if (content instanceof MCPSchema.ImageContent imageContent) {
                if (sb.length() > 0) sb.append("\n");
                String mimeType = imageContent.getMimeType();
                if (mimeType == null || mimeType.isEmpty()) {
                    mimeType = "image/png";
                }
                sb.append(String.format("data:%s;base64,%s", mimeType, imageContent.getData()));
            } else if (content instanceof MCPSchema.EmbeddedResource embeddedResource) {
                MCPSchema.ResourceContents resource = embeddedResource.getResource();
                if (resource != null && resource.getBlob() != null) {
                    String mimeType = resource.getMimeType();
                    if (mimeType != null && mimeType.startsWith("image/")) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(String.format("data:%s;base64,%s", mimeType, resource.getBlob()));
                    }
                }
            }
        }

        return ToolResult.success(sb.toString());
    }

    /**
     * 从内容列表中提取文本
     */
    private static String extractText(List<MCPSchema.Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MCPSchema.Content content : contents) {
            if (content instanceof MCPSchema.TextContent textContent) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(textContent.getText());
            }
        }
        return sb.toString();
    }
}
