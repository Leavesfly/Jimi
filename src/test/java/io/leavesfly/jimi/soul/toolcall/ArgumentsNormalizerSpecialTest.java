package io.leavesfly.jimi.soul.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.engine.toolcall.ArgumentsNormalizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 针对特定场景的 ArgumentsNormalizer 测试
 * 测试包含路径和内容的JSON字符串处理
 */
class ArgumentsNormalizerSpecialTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testJsonWithEscapedNewlinesAndQuotes() {
        // 测试用例1: 已经正确转义的JSON（包含 \n 和 \" ）
        String input = "{\"path\": \"/test.java\", \"content\": \"public class Test {\\n    private String name = \\\"value\\\";\\n}\"}";
        
        System.out.println("\n=== 测试用例1：已正确转义的JSON ===");
        System.out.println("输入: " + input);
        
        // 首先验证输入是否已经是有效的JSON
        try {
            var originalTree = objectMapper.readTree(input);
            System.out.println("✓ 输入已经是有效的JSON");
            System.out.println("原始内容字段:");
            System.out.println(originalTree.get("content").asText());
        } catch (Exception e) {
            System.out.println("✗ 输入不是有效的JSON: " + e.getMessage());
        }
        
        // 测试 normalizeToValidJson 方法
        String result = ArgumentsNormalizer.normalizeToValidJson(input, objectMapper);
        System.out.println("\n处理后结果: " + result);
        
        // 验证结果是否为有效的JSON
        assertDoesNotThrow(() -> {
            var tree = objectMapper.readTree(result);
            System.out.println("\n✓ 处理后的结果是有效的JSON");
            System.out.println("path: " + tree.get("path").asText());
            System.out.println("content: " + tree.get("content").asText());
            
            // 验证内容是否正确保留
            assertEquals("/test.java", tree.get("path").asText());
            String content = tree.get("content").asText();
            assertTrue(content.contains("public class Test"));
            assertTrue(content.contains("private String name = \"value\";"));
        });
    }

    @Test
    void testJsonWithActualNewlinesAndUnescapedQuotes() {
        // 测试用例2: 包含实际换行符和未转义引号的JSON（这是无效的JSON）
        String input = "{\"path\": \"/test.java\", \"content\": \"public class Test {\n    private String name = \"value\";\n}\"}";
        
        System.out.println("\n=== 测试用例2：包含实际换行符和未转义引号的JSON ===");
        System.out.println("输入（显示转义后）: " + input.replace("\n", "\\n").replace("\t", "\\t"));
        
        // 验证输入是否为无效的JSON
        try {
            objectMapper.readTree(input);
            System.out.println("输入竟然是有效的JSON（不符合预期）");
        } catch (Exception e) {
            System.out.println("✓ 输入是无效的JSON: " + e.getMessage());
        }
        
        // 测试 normalizeToValidJson 方法能否修复
        String result = ArgumentsNormalizer.normalizeToValidJson(input, objectMapper);
        System.out.println("\n处理后结果: " + result.replace("\n", "\\n"));
        
        // 验证结果是否为有效的JSON
        assertDoesNotThrow(() -> {
            var tree = objectMapper.readTree(result);
            System.out.println("\n✓ 处理后的结果是有效的JSON");
            System.out.println("path: " + tree.get("path").asText());
            System.out.println("content 原始值:");
            System.out.println(tree.get("content").asText());
        }, "normalizeToValidJson应该能够修复包含实际换行符和未转义引号的JSON");
    }

//    @Test
//    void testDoubleEscapedJson() {
//        // 测试用例3: 双重转义的JSON（整个JSON字符串被转义）
//        String input = "\"{\\\"path\\\": \\\"/test.java\\\", \\\"content\\\": \\\"public class Test {\\\\n    private String name = \\\\\\\"value\\\\\\\";\\\\n}\\\"}\"";
//
//        System.out.println("\n=== 测试用例3：双重转义的JSON ===");
//        System.out.println("输入: " + input);
//
//        String result = ArgumentsNormalizer.normalizeToValidJson(input, objectMapper);
//        System.out.println("处理后结果: " + result);
//
//        assertDoesNotThrow(() -> {
//            var tree = objectMapper.readTree(result);
//            System.out.println("\n✓ 处理后的结果是有效的JSON");
//            assertEquals("/test.java", tree.get("path").asText());
//        });
//    }

    @Test
    void testVariousNewlineFormats() {
        // 测试用例4: 各种换行符格式
        String[] inputs = {
            "{\"content\": \"line1\\nline2\"}",  // 已转义的\n
            "{\"content\": \"line1\\r\\nline2\"}",  // 已转义的\r\n
            "{\"content\": \"line1\nline2\"}",  // 实际的\n
            "{\"content\": \"line1\r\nline2\"}"  // 实际的\r\n
        };
        
        System.out.println("\n=== 测试用例4：各种换行符格式 ===");
        for (int i = 0; i < inputs.length; i++) {
            String input = inputs[i];
            System.out.println("\n子测试 " + (i+1) + ":");
            System.out.println("输入: " + input.replace("\n", "\\n").replace("\r", "\\r"));
            
            String result = ArgumentsNormalizer.normalizeToValidJson(input, objectMapper);
            System.out.println("结果: " + result.replace("\n", "\\n").replace("\r", "\\r"));
            
            try {
                var tree = objectMapper.readTree(result);
                System.out.println("✓ 有效的JSON，content值: " + tree.get("content").asText().replace("\n", "\\n").replace("\r", "\\r"));
            } catch (Exception e) {
                System.out.println("✗ 无效的JSON: " + e.getMessage());
            }
        }
    }
    
    @Test
    void testComplexJavaCodeContent() {
        // 测试用例5: 更复杂的Java代码内容
        String javaCode = "public class Test {\n" +
                          "    private String name = \"value\";\n" +
                          "    public String getName() {\n" +
                          "        return \"Hello, \" + name;\n" +
                          "    }\n" +
                          "}";
        
        // 场景A: 已正确转义
        String inputA = "{\"path\": \"/test.java\", \"content\": \"" + 
                       javaCode.replace("\\", "\\\\")
                              .replace("\"", "\\\"")
                              .replace("\n", "\\n")
                              .replace("\r", "\\r")
                              .replace("\t", "\\t") + 
                       "\"}";
        
        System.out.println("\n=== 测试用例5A：复杂Java代码（已转义） ===");
        System.out.println("输入长度: " + inputA.length());
        
        String resultA = ArgumentsNormalizer.normalizeToValidJson(inputA, objectMapper);
        assertDoesNotThrow(() -> {
            var tree = objectMapper.readTree(resultA);
            System.out.println("✓ 处理成功");
            System.out.println("content字段内容预览: " + tree.get("content").asText().substring(0, 50) + "...");
        });
        
        // 场景B: 未转义（包含实际换行符和引号）
        String inputB = "{\"path\": \"/test.java\", \"content\": \"" + javaCode + "\"}";
        
        System.out.println("\n=== 测试用例5B：复杂Java代码（未转义） ===");
        
        String resultB = ArgumentsNormalizer.normalizeToValidJson(inputB, objectMapper);
        assertDoesNotThrow(() -> {
            var tree = objectMapper.readTree(resultB);
            System.out.println("✓ 处理成功（修复了未转义的内容）");
        });
    }
}
