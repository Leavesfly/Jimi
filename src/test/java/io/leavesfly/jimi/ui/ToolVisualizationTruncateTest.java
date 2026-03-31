package io.leavesfly.jimi.ui;

import io.leavesfly.jimi.llm.message.FunctionCall;
import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.tool.ToolResult;

import io.leavesfly.jimi.ui.shell.output.ToolVisualization;
import org.junit.jupiter.api.Test;

/**
 * 测试 ToolVisualization 的文本截取功能
 * 
 * @author 山泽
 */
class ToolVisualizationTruncateTest {
    
    /**
     * 测试长路径截取 - 优先保留文件名
     */
    @Test
    void testLongPathTruncation() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("测试 1: 长路径截取 - 优先保留文件名");
        System.out.println("=".repeat(70) + "\n");
        
        ToolVisualization viz = new ToolVisualization();
        
        // 很长的文件路径
        ToolCall call = ToolCall.builder()
                .id("call_001")
                .type("function")
                .function(FunctionCall.builder()
                        .name("ReadFile")
                        .arguments("{\"path\":\"/Users/developer/projects/my-awesome-project/src/main/java/com/example/service/impl/UserServiceImpl.java\"}")
                        .build())
                .build();
        
        viz.onToolCallStart(call);
        Thread.sleep(300);
        viz.onToolCallComplete("call_001", ToolResult.ok("文件内容...", "读取了 200 行"));
        
        System.out.println("\n✅ 长路径截取测试完成\n");
    }
    
    /**
     * 测试长命令截取 - 在空格处智能断开
     */
    @Test
    void testLongCommandTruncation() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("测试 2: 长命令截取 - 智能断开");
        System.out.println("=".repeat(70) + "\n");
        
        ToolVisualization viz = new ToolVisualization();
        
        // 很长的 BashTool 命令
        ToolCall call = ToolCall.builder()
                .id("call_002")
                .type("function")
                .function(FunctionCall.builder()
                        .name("BashTool")
                        .arguments("{\"command\":\"find /Users/developer/projects -type f -name '*.java' | xargs grep -l 'public class' | sort | uniq\"}")
                        .build())
                .build();
        
        viz.onToolCallStart(call);
        Thread.sleep(300);
        viz.onToolCallComplete("call_002", ToolResult.ok("结果...", "找到 42 个文件"));
        
        System.out.println("\n✅ 长命令截取测试完成\n");
    }
    
    /**
     * 测试长查询截取 - 在标点处断开
     */
    @Test
    void testLongQueryTruncation() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("测试 3: 长查询截取 - 在标点处断开");
        System.out.println("=".repeat(70) + "\n");
        
        ToolVisualization viz = new ToolVisualization();
        
        // 很长的搜索查询
        ToolCall call = ToolCall.builder()
                .id("call_003")
                .type("function")
                .function(FunctionCall.builder()
                        .name("SearchWeb")
                        .arguments("{\"query\":\"如何在 Spring Boot 项目中实现基于 JWT 的身份验证和授权机制，包括刷新令牌的处理和安全最佳实践？\"}")
                        .build())
                .build();
        
        viz.onToolCallStart(call);
        Thread.sleep(300);
        viz.onToolCallComplete("call_003", ToolResult.ok("...", "找到 10 个相关结果"));
        
        System.out.println("\n✅ 长查询截取测试完成\n");
    }
    
    /**
     * 测试长结果摘要截取
     */
    @Test
    void testLongBriefTruncation() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("测试 4: 长结果摘要截取");
        System.out.println("=".repeat(70) + "\n");
        
        ToolVisualization viz = new ToolVisualization();
        
        ToolCall call = ToolCall.builder()
                .id("call_004")
                .type("function")
                .function(FunctionCall.builder()
                        .name("WriteFile")
                        .arguments("{\"path\":\"/tmp/test.txt\"}")
                        .build())
                .build();
        
        // 很长的结果摘要
        String longBrief = "成功写入文件 /tmp/test.txt，共计 1234 字节。文件包含了用户配置信息、系统参数设置以及多个模块的初始化数据。所有数据已经过验证并格式化为标准 JSON 格式。";
        
        viz.onToolCallStart(call);
        Thread.sleep(300);
        viz.onToolCallComplete("call_004", ToolResult.ok("", longBrief));
        
        System.out.println("\n✅ 长结果摘要截取测试完成\n");
    }
    
    /**
     * 综合测试 - 多个长参数工具
     */
    @Test
    void testMultipleLongParameters() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("测试 5: 综合测试 - 多个长参数工具");
        System.out.println("=".repeat(70) + "\n");
        
        ToolVisualization viz = new ToolVisualization();
        
        ToolCall[] calls = {
            ToolCall.builder()
                .id("call_101")
                .function(FunctionCall.builder()
                    .name("ReadFile")
                    .arguments("{\"path\":\"/very/long/path/to/my/important/document/that/has/a/very/descriptive/name/config.yaml\"}")
                    .build())
                .build(),
            ToolCall.builder()
                .id("call_102")
                .function(FunctionCall.builder()
                    .name("BashTool")
                    .arguments("{\"command\":\"docker-compose -f docker-compose.prod.yml up -d --build --force-recreate --remove-orphans\"}")
                    .build())
                .build(),
            ToolCall.builder()
                .id("call_103")
                .function(FunctionCall.builder()
                    .name("SubAgentTool")
                    .arguments("{\"description\":\"请分析项目的整体架构设计，识别潜在的性能瓶颈和安全隐患，并提供详细的优化建议和改进方案\"}")
                    .build())
                .build()
        };
        
        // 启动所有工具
        for (ToolCall call : calls) {
            viz.onToolCallStart(call);
        }
        
        Thread.sleep(200);
        viz.onToolCallComplete("call_101", ToolResult.ok("...", "成功读取配置文件，包含 50 个配置项"));
        
        Thread.sleep(200);
        viz.onToolCallComplete("call_102", ToolResult.ok("...", "Docker 容器已启动，所有服务运行正常"));
        
        Thread.sleep(200);
        viz.onToolCallComplete("call_103", ToolResult.ok("...", "架构分析完成，已生成详细报告"));
        
        System.out.println("\n✅ 综合测试完成\n");
    }
}
