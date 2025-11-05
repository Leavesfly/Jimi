package io.leavesfly.jimi.ui;

import io.leavesfly.jimi.llm.message.FunctionCall;
import io.leavesfly.jimi.llm.message.ToolCall;
import io.leavesfly.jimi.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * 工具可视化完整演示
 * 
 * 展示工具执行的实时可视化效果：
 * 1. 单个工具执行
 * 2. 多个工具并行执行
 * 3. 成功和失败场景
 * 4. 不同工具类型的展示
 * 5. 性能测试
 * 
 * @author 山泽
 */
class ToolVisualizationDemo {
    
    /**
     * 演示 1: 基本工具执行可视化
     */
    @Test
    void demo1_BasicToolVisualization() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("演示 1: 基本工具执行可视化");
        System.out.println("=".repeat(70) + "\n");
        
        ToolVisualization viz = new ToolVisualization();
        
        // 模拟 ReadFile 工具调用
        ToolCall readFileCall = ToolCall.builder()
                .id("call_001")
                .type("function")
                .function(FunctionCall.builder()
                        .name("ReadFile")
                        .arguments("{\"path\":\"/path/to/example.txt\"}")
                        .build())
                .build();
        
        System.out.println("开始执行工具...\n");
        viz.onToolCallStart(readFileCall);
        
        // 模拟执行时间
        Thread.sleep(500);
        
        // 完成
        ToolResult result = ToolResult.ok("文件内容...", "读取了 100 行");
        viz.onToolCallComplete("call_001", result);
        
        System.out.println("\n✅ 基本可视化演示完成\n");
    }
    
    /**
     * 演示 2: 多个工具并行执行
     */
    @Test
    void demo2_ParallelToolExecution() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("演示 2: 多个工具并行执行");
        System.out.println("=".repeat(70) + "\n");
        
        ToolVisualization viz = new ToolVisualization();
        
        // 启动 3 个工具
        ToolCall[] calls = {
                createToolCall("call_101", "ReadFile", "{\"path\":\"/src/main.java\"}"),
                createToolCall("call_102", "SearchWeb", "{\"query\":\"Java best practices\"}"),
                createToolCall("call_103", "Bash", "{\"command\":\"ls -la\"}")
        };
        
        System.out.println("并行执行 3 个工具...\n");
        
        for (ToolCall call : calls) {
            viz.onToolCallStart(call);
        }
        
        // 模拟不同的完成时间
        Thread.sleep(300);
        viz.onToolCallComplete("call_101", ToolResult.ok("...", "文件已读取"));
        
        Thread.sleep(200);
        viz.onToolCallComplete("call_103", ToolResult.ok("...", "命令执行成功"));
        
        Thread.sleep(400);
        viz.onToolCallComplete("call_102", ToolResult.ok("...", "找到 5 个结果"));
        
        System.out.println("\n✅ 并行执行演示完成\n");
    }
    
    /**
     * 演示 3: 成功和失败场景
     */
    @Test
    void demo3_SuccessAndFailureScenarios() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("演示 3: 成功和失败场景");
        System.out.println("=".repeat(70) + "\n");
        
        ToolVisualization viz = new ToolVisualization();
        
        // 场景 1: 成功
        System.out.println("场景 1: 成功执行");
        ToolCall successCall = createToolCall("call_201", "WriteFile", 
                "{\"path\":\"/tmp/test.txt\",\"content\":\"Hello\"}");
        
        viz.onToolCallStart(successCall);
        Thread.sleep(200);
        viz.onToolCallComplete("call_201", 
                ToolResult.ok("", "文件已写入 (5 字节)"));
        
        System.out.println();
        
        // 场景 2: 失败
        System.out.println("场景 2: 执行失败");
        ToolCall failureCall = createToolCall("call_202", "ReadFile", 
                "{\"path\":\"/nonexistent.txt\"}");
        
        viz.onToolCallStart(failureCall);
        Thread.sleep(150);
        viz.onToolCallComplete("call_202", 
                ToolResult.error("文件不存在", "File not found"));
        
        System.out.println("\n✅ 成功/失败场景演示完成\n");
    }
    
    /**
     * 演示 4: 不同工具类型的可视化
     */
    @Test
    void demo4_DifferentToolTypes() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("演示 4: 不同工具类型的可视化");
        System.out.println("=".repeat(70) + "\n");
        
        ToolVisualization viz = new ToolVisualization();
        
        // 1. 文件工具
        System.out.println("1. 文件工具:");
        executeAndDisplay(viz, "call_301", "ReadFile", 
                "{\"path\":\"/src/UserService.java\"}", 
                ToolResult.ok("...", "读取了 150 行"));
        
        System.out.println();
        
        // 2. 网络工具
        System.out.println("2. 网络工具:");
        executeAndDisplay(viz, "call_302", "FetchURL", 
                "{\"url\":\"https://example.com\"}", 
                ToolResult.ok("...", "获取了 2KB 内容"));
        
        System.out.println();
        
        // 3. Shell 工具
        System.out.println("3. Shell 工具:");
        executeAndDisplay(viz, "call_303", "Bash", 
                "{\"command\":\"find . -name '*.java' | wc -l\"}", 
                ToolResult.ok("42", "找到 42 个文件"));
        
        System.out.println();
        
        // 4. Task 工具
        System.out.println("4. Task 工具:");
        executeAndDisplay(viz, "call_304", "Task", 
                "{\"description\":\"Fix bug\",\"subagent_name\":\"code_fixer\"}", 
                ToolResult.ok("...", "子 Agent 任务完成"));
        
        System.out.println();
        
        // 5. Think 工具
        System.out.println("5. Think 工具:");
        executeAndDisplay(viz, "call_305", "Think", 
                "{\"thought\":\"我需要先分析代码结构\"}", 
                ToolResult.ok("", "思考已记录"));
        
        System.out.println("\n✅ 不同工具类型演示完成\n");
    }
    
    /**
     * 演示 5: 实时进度更新
     */
    @Test
    void demo5_RealtimeProgressUpdate() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("演示 5: 实时进度更新");
        System.out.println("=".repeat(70) + "\n");
        
        ToolVisualization viz = new ToolVisualization();
        
        ToolCall longRunningCall = createToolCall("call_401", "Bash", 
                "{\"command\":\"sleep 2 && echo done\"}");
        
        System.out.println("执行长时间运行的工具（观察旋转动画）...\n");
        viz.onToolCallStart(longRunningCall);
        
        // 模拟长时间执行（显示旋转动画）
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100);
            System.out.print("\r\033[K");  // 清除当前行
            System.out.print(getCurrentDisplay(viz, "call_401"));
            System.out.flush();
        }
        
        System.out.println();  // 换行
        viz.onToolCallComplete("call_401", ToolResult.ok("done", "命令执行成功"));
        
        System.out.println("\n✅ 实时进度更新演示完成\n");
    }
    
    /**
     * 演示 6: 复杂场景 - 模拟真实 Agent 执行
     */
    @Test
    void demo6_RealWorldScenario() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("演示 6: 复杂场景 - 模拟真实 Agent 执行");
        System.out.println("=".repeat(70) + "\n");
        
        ToolVisualization viz = new ToolVisualization();
        
        System.out.println("用户请求: 分析并修复 UserService.java 中的 Bug\n");
        System.out.println("Agent 执行过程:\n");
        
        // Step 1: 思考
        executeAndDisplay(viz, "call_501", "Think", 
                "{\"thought\":\"我需要先读取文件内容\"}", 
                ToolResult.ok("", "思考已记录"));
        Thread.sleep(200);
        
        // Step 2: 读取文件
        executeAndDisplay(viz, "call_502", "ReadFile", 
                "{\"path\":\"/src/main/java/UserService.java\"}", 
                ToolResult.ok("...", "读取了 200 行"));
        Thread.sleep(300);
        
        // Step 3: 思考
        executeAndDisplay(viz, "call_503", "Think", 
                "{\"thought\":\"发现空指针异常风险\"}", 
                ToolResult.ok("", "思考已记录"));
        Thread.sleep(200);
        
        // Step 4: 搜索最佳实践
        executeAndDisplay(viz, "call_504", "SearchWeb", 
                "{\"query\":\"Java null check best practices\"}", 
                ToolResult.ok("...", "找到 3 个相关结果"));
        Thread.sleep(400);
        
        // Step 5: 应用补丁
        executeAndDisplay(viz, "call_505", "PatchFile", 
                "{\"path\":\"/src/main/java/UserService.java\",\"diff\":\"...\"}", 
                ToolResult.ok("", "成功应用 2 个 hunks"));
        Thread.sleep(300);
        
        // Step 6: 验证
        executeAndDisplay(viz, "call_506", "Bash", 
                "{\"command\":\"mvn test -Dtest=UserServiceTest\"}", 
                ToolResult.ok("...", "测试通过 (3/3)"));
        Thread.sleep(500);
        
        System.out.println("\n✅ Bug 已修复并验证！\n");
    }
    
    /**
     * 演示 7: 性能测试
     */
    @Test
    void demo7_PerformanceTest() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("演示 7: 性能测试");
        System.out.println("=".repeat(70) + "\n");
        
        ToolVisualization viz = new ToolVisualization();
        
        int toolCount = 100;
        long startTime = System.currentTimeMillis();
        
        System.out.println("执行 " + toolCount + " 个工具调用...\n");
        
        for (int i = 0; i < toolCount; i++) {
            String callId = "call_" + (700 + i);
            ToolCall call = createToolCall(callId, "Think", 
                    "{\"thought\":\"思考 " + i + "\"}");
            
            viz.onToolCallStart(call);
            viz.onToolCallComplete(callId, ToolResult.ok("", "完成"));
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        System.out.println("\n性能统计:");
        System.out.println("  总工具数: " + toolCount);
        System.out.println("  总耗时: " + duration + "ms");
        System.out.println("  平均耗时: " + (duration / toolCount) + "ms/工具");
        System.out.println("  吞吐量: " + (toolCount * 1000 / duration) + " 工具/秒");
        
        System.out.println("\n✅ 性能测试完成\n");
    }
    
    /**
     * 演示 8: 功能总结
     */
    @Test
    void demo8_Summary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("工具可视化功能总结");
        System.out.println("=".repeat(70) + "\n");
        
        System.out.println("核心特性:");
        System.out.println("  1. ✅ 实时进度显示");
        System.out.println("     - 旋转动画（10 帧）");
        System.out.println("     - 工具名称和参数摘要");
        System.out.println("     - 执行时间统计");
        
        System.out.println("\n  2. ✅ 彩色输出");
        System.out.println("     - 🔵 蓝色 - 工具名称");
        System.out.println("     - ⚪ 灰色 - 参数摘要");
        System.out.println("     - 🔄 青色 - 旋转动画");
        System.out.println("     - ✅ 绿色 - 成功标识");
        System.out.println("     - ✗ 红色 - 失败标识");
        
        System.out.println("\n  3. ✅ 智能摘要提取");
        System.out.println("     - ReadFile → 显示文件路径");
        System.out.println("     - Bash → 显示命令");
        System.out.println("     - SearchWeb → 显示查询");
        System.out.println("     - Task → 显示任务描述");
        
        System.out.println("\n  4. ✅ 结果展示");
        System.out.println("     - 成功/失败状态");
        System.out.println("     - 结果摘要");
        System.out.println("     - 执行时间");
        
        System.out.println("\n技术实现:");
        System.out.println("  - ANSI 转义码（彩色输出）");
        System.out.println("  - Unicode 字符（旋转动画）");
        System.out.println("  - 正则表达式（参数提取）");
        System.out.println("  - 实时更新（\\r\\033[K）");
        
        System.out.println("\n用户体验提升:");
        System.out.println("  ✓ 即时反馈 - 用户立即看到工具开始执行");
        System.out.println("  ✓ 进度感知 - 旋转动画表明系统正在工作");
        System.out.println("  ✓ 信息透明 - 清晰显示工具名称和参数");
        System.out.println("  ✓ 结果明确 - 成功/失败一目了然");
        
        System.out.println("\n与 Python 版本对比:");
        System.out.println("  功能对等 ✅");
        System.out.println("  使用 ANSI 颜色（vs Rich library）✅");
        System.out.println("  旋转动画 ✅");
        System.out.println("  参数提取 ✅");
        System.out.println("  执行时间统计 ✅");
        
        System.out.println("\n" + "=".repeat(70) + "\n");
    }
    
    // ==================== 辅助方法 ====================
    
    private ToolCall createToolCall(String id, String name, String arguments) {
        return ToolCall.builder()
                .id(id)
                .type("function")
                .function(FunctionCall.builder()
                        .name(name)
                        .arguments(arguments)
                        .build())
                .build();
    }
    
    private void executeAndDisplay(ToolVisualization viz, String callId, 
                                   String toolName, String arguments, ToolResult result) 
            throws InterruptedException {
        ToolCall call = createToolCall(callId, toolName, arguments);
        viz.onToolCallStart(call);
        Thread.sleep(200);  // 模拟执行时间
        viz.onToolCallComplete(callId, result);
    }
    
    private String getCurrentDisplay(ToolVisualization viz, String callId) {
        // 简化版本 - 实际应该从 viz 获取当前显示
        return "  (执行中...)";
    }
}
