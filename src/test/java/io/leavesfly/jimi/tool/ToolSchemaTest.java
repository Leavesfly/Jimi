package io.leavesfly.jimi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.tool.bash.Bash;
import io.leavesfly.jimi.tool.file.ReadFile;
import io.leavesfly.jimi.tool.file.WriteFile;
import io.leavesfly.jimi.tool.think.Think;

/**
 * 工具 Schema 测试
 * 验证生成的 OpenAPI Schema 是否包含参数描述
 */
public class ToolSchemaTest {
    
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("工具 Schema 描述验证测试");
        System.out.println("=".repeat(80));
        System.out.println();
        
        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry(objectMapper);
        
        // 注册一些工具进行测试
        registry.register(new WriteFile());
        registry.register(new ReadFile());
        registry.register(new Bash());
        registry.register(new Think());
        
        // 获取 JSON Schema (传入 null 获取所有工具的 schema)
        java.util.List<JsonNode> schemaList = registry.getToolSchemas(null);
        
        System.out.println("生成的工具 Schema:");
        System.out.println("=".repeat(80));
        
        try {
            // 将 List 转换为 ArrayNode 进行美化输出
            com.fasterxml.jackson.databind.node.ArrayNode arrayNode = objectMapper.createArrayNode();
            schemaList.forEach(arrayNode::add);
            
            // 美化输出
            String prettyJson = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(arrayNode);
            System.out.println(prettyJson);
        } catch (Exception e) {
            System.err.println("JSON 格式化失败: " + e.getMessage());
            System.out.println(schemaList.toString());
        }
        
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("验证要点:");
        System.out.println("  ✓ 检查 function.parameters.properties 中的每个参数是否包含 description 字段");
        System.out.println("  ✓ 确认 description 内容是否清晰准确");
        System.out.println("  ✓ 验证必填参数是否在 required 数组中");
        System.out.println("  ✓ 确认可选参数（带 @Builder.Default）不在 required 数组中");
        System.out.println("=".repeat(80));
    }
}
