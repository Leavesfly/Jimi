package io.leavesfly.jimi.tool.mcp;

import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.ImagePart;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 结果转换器 - 轻量级本地实现
 * 不依赖io.modelcontextprotocol.sdk，直接处理本地Schema对象
 * 
 * 主要功能：
 * 1. 将MCP工具调用结果转换为Jimi的ToolResult格式
 * 2. 处理多种内容类型：文本、图片、嵌入资源
 * 3. 将图片数据转换为Data URL格式
 */
@Slf4j
public class MCPResultConverter {
    /**
     * 转换MCP调用结果为ToolResult
     * 
     * @param mcpResult MCP工具调用结果
     * @return Jimi的ToolResult对象
     */
    public static ToolResult convert(MCPSchema.CallToolResult mcpResult) {
        if (mcpResult == null) {
            return ToolResult.error("MCP result is null", "Empty result");
        }
        
        // 检查是否为错误结果
        if (Boolean.TRUE.equals(mcpResult.getIsError())) {
            return ToolResult.error("MCP tool returned error", "Tool execution error");
        }
        
        List<MCPSchema.Content> contents = mcpResult.getContent();
        if (contents == null || contents.isEmpty()) {
            return ToolResult.ok("", "");
        }
        
        // 转换每个内容项
        List<ContentPart> contentParts = new ArrayList<>();
        for (MCPSchema.Content content : contents) {
            ContentPart part = convertContentPart(content);
            if (part != null) {
                contentParts.add(part);
            }
        }
        
        // 单文本内容简化处理：直接返回文本
        if (contentParts.size() == 1 && contentParts.get(0) instanceof TextPart textPart) {
            return ToolResult.ok(textPart.getText(), "");
        }
        
        // 多内容合并处理：拼接所有文本和图片URL
        StringBuilder sb = new StringBuilder();
        for (ContentPart p : contentParts) {
            if (p instanceof TextPart tp && tp.getText() != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(tp.getText());
            } else if (p instanceof ImagePart ip && ip.getUrl() != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(ip.getUrl());
            } else if (p != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(p.toString());
            }
        }
        
        return ToolResult.ok(sb.toString(), "");
    }

    /**
     * 转换单个内容项
     * 根据实际类型转换为对应的ContentPart子类
     * 
     * @param content MCP内容对象
     * @return Jimi的ContentPart对象
     */
    private static ContentPart convertContentPart(MCPSchema.Content content) {
        if (content instanceof MCPSchema.TextContent textContent) {
            return TextPart.of(textContent.getText());
        } else if (content instanceof MCPSchema.ImageContent imageContent) {
            return convertImageContent(imageContent);
        } else if (content instanceof MCPSchema.EmbeddedResource embeddedResource) {
            return convertEmbeddedResource(embeddedResource);
        }
        return null;
    }

    /**
     * 转换图片内容
     * 将Base64编码的图片数据转换为Data URL格式
     * 
     * @param imageContent 图片内容对象
     * @return 图片ContentPart
     */
    private static ImagePart convertImageContent(MCPSchema.ImageContent imageContent) {
        String mimeType = imageContent.getMimeType();
        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = "image/png";  // 默认MIME类型
        }
        String data = imageContent.getData();
        // 构造Data URL: data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA...
        String dataUrl = String.format("data:%s;base64,%s", mimeType, data);
        return ImagePart.of(dataUrl);
    }

    /**
     * 转换嵌入资源
     * 处理嵌入在响应中的外部资源，目前仅支持图片类型
     * 
     * @param embeddedResource 嵌入资源对象
     * @return ContentPart对象，仅处理图片资源
     */
    private static ContentPart convertEmbeddedResource(MCPSchema.EmbeddedResource embeddedResource) {
        MCPSchema.ResourceContents resource = embeddedResource.getResource();
        if (resource == null) {
            return null;
        }
        
        String mimeType = resource.getMimeType();
        // 仅处理图片类型的资源
        if (mimeType != null && mimeType.startsWith("image/")) {
            String blob = resource.getBlob();
            if (blob != null) {
                String dataUrl = String.format("data:%s;base64,%s", mimeType, blob);
                return ImagePart.of(dataUrl);
            }
        }
        
        return null;  // 非图片资源暂不支持
    }
}
