package io.leavesfly.jimi.tool.meta;

import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * JShellCodeExecutor 单元测试
 */
@ExtendWith(MockitoExtension.class)
class JShellCodeExecutorTest {
    
    @Mock
    private ToolRegistry mockToolRegistry;
    
    private JShellCodeExecutor executor;
    
    @BeforeEach
    void setUp() {
        executor = new JShellCodeExecutor();
    }
    
    @Test
    void testSimpleExpression() {
        // 准备
        CodeExecutionContext context = CodeExecutionContext.builder()
                .code("1 + 1")
                .timeout(10)
                .toolRegistry(mockToolRegistry)
                .logExecutionDetails(false)
                .build();
        
        // 执行
        String result = executor.execute(context).block();
        
        // 验证
        assertNotNull(result);
        assertTrue(result.contains("2") || result.equals("2"));
    }
    
    @Test
    void testStringConcatenation() {
        // 准备
        CodeExecutionContext context = CodeExecutionContext.builder()
                .code("\"Hello\" + \" \" + \"World\"")
                .timeout(10)
                .toolRegistry(mockToolRegistry)
                .logExecutionDetails(false)
                .build();
        
        // 执行
        String result = executor.execute(context).block();
        
        // 验证
        assertNotNull(result);
        assertTrue(result.contains("Hello World"));
    }
    
    @Test
    void testLoopExecution() {
        // 准备
        String code = """
                StringBuilder result = new StringBuilder();
                for (int i = 1; i <= 3; i++) {
                    result.append(i).append(" ");
                }
                result.toString()
                """;
        
        CodeExecutionContext context = CodeExecutionContext.builder()
                .code(code)
                .timeout(10)
                .toolRegistry(mockToolRegistry)
                .logExecutionDetails(false)
                .build();
        
        // 执行
        String result = executor.execute(context).block();
        
        // 验证
        assertNotNull(result);
        assertTrue(result.contains("1") && result.contains("2") && result.contains("3"));
    }
    
    @Test
    void testToolBridgeCall() {
        // 准备模拟工具调用
        when(mockToolRegistry.execute(eq("TestTool"), anyString()))
                .thenReturn(Mono.just(ToolResult.ok("Mock output", "Success")));
        
        String code = """
                String result = callTool("TestTool", "{\\"test\\":\\"value\\"}");
                return result;
                """;
        
        CodeExecutionContext context = CodeExecutionContext.builder()
                .code(code)
                .timeout(10)
                .toolRegistry(mockToolRegistry)
                .logExecutionDetails(true)
                .build();
        
        // 执行
        String result = executor.execute(context).block();
        
        // 验证
        assertNotNull(result);
        verify(mockToolRegistry, times(1)).execute(eq("TestTool"), anyString());
    }
    
    @Test
    void testCodeSafetyValidation_Safe() {
        String safeCode = "int x = 1 + 1; return x;";
        assertTrue(JShellCodeExecutor.validateCodeSafety(safeCode));
    }
    
    @Test
    void testCodeSafetyValidation_Dangerous() {
        String dangerousCode1 = "System.exit(0);";
        assertFalse(JShellCodeExecutor.validateCodeSafety(dangerousCode1));
        
        String dangerousCode2 = "Runtime.getRuntime().exec(\"rm -rf /\");";
        assertFalse(JShellCodeExecutor.validateCodeSafety(dangerousCode2));
        
        String dangerousCode3 = "ProcessBuilder pb = new ProcessBuilder();";
        assertFalse(JShellCodeExecutor.validateCodeSafety(dangerousCode3));
    }
    
    @Test
    void testTimeout() {
        // 准备一个无限循环的代码
        String code = "while(true) { }";
        
        CodeExecutionContext context = CodeExecutionContext.builder()
                .code(code)
                .timeout(2)  // 2秒超时
                .toolRegistry(mockToolRegistry)
                .logExecutionDetails(false)
                .build();
        
        // 执行
        String result = executor.execute(context).block();
        
        // 验证 - 应该超时
        assertNotNull(result);
        assertTrue(result.contains("timeout") || result.contains("Timeout"));
    }
    
    @Test
    void testCompilationError() {
        // 准备一个有语法错误的代码
        String code = "int x = ; // 语法错误";
        
        CodeExecutionContext context = CodeExecutionContext.builder()
                .code(code)
                .timeout(10)
                .toolRegistry(mockToolRegistry)
                .logExecutionDetails(false)
                .build();
        
        // 执行
        String result = executor.execute(context).block();
        
        // 验证 - 应该有错误信息
        assertNotNull(result);
        // JShell 可能会报告编译错误
    }
}
