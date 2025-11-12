package io.leavesfly.jimi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.tool.file.StrReplaceFile;

/**
 * StrReplaceFile 工具 Schema 测试
 * 验证生成的 Schema 是否正确处理嵌套对象
 */
public class StrReplaceFileSchemaTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(80));
        System.out.println("StrReplaceFile 工具 Schema 测试");
        System.out.println("=".repeat(80));
        System.out.println();
        
        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry(objectMapper);
        
        // 注册 StrReplaceFile 工具
        StrReplaceFile tool = new StrReplaceFile();
        registry.register(tool);
        
        // 获取 JSON Schema
        JsonNode schema = registry.getToolSchemas(null).get(0);
        
        System.out.println("生成的 StrReplaceFile Schema:");
        System.out.println("=".repeat(80));
        
        // 美化输出
        String prettyJson = objectMapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(schema);
        System.out.println(prettyJson);
        
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("验证要点:");
        System.out.println("  ✓ edits 字段的 type 应该是 'array'");
        System.out.println("  ✓ edits.items 应该是一个 object，而不是 string");
        System.out.println("  ✓ edits.items.properties 应该包含 old, newText, replaceAll");
        System.out.println("  ✓ edits.items.required 应该只包含 'old'");
        System.out.println("=".repeat(80));
        
        // 验证关键结构
        JsonNode editsField = schema.at("/function/parameters/properties/edits");
        System.out.println();
        System.out.println("验证结果:");
        System.out.println("  edits.type = " + editsField.get("type").asText());
        System.out.println("  edits.items.type = " + editsField.at("/items/type").asText());
        
        if (editsField.at("/items/properties").has("old")) {
            System.out.println("  ✓ edits.items.properties 包含 'old' 字段");
        }
        if (editsField.at("/items/properties").has("newText")) {
            System.out.println("  ✓ edits.items.properties 包含 'newText' 字段");
        }
        if (editsField.at("/items/properties").has("replaceAll")) {
            System.out.println("  ✓ edits.items.properties 包含 'replaceAll' 字段");
        }
    }
}
