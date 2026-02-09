package io.leavesfly.jimi.adk.tools.extended.meta;

import jdk.jshell.*;
import jdk.jshell.execution.LocalExecutionControlProvider;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * JShell 代码执行引擎
 * 
 * 使用 JShell API 执行 Java 代码片段
 * 支持超时控制和工具调用桥接
 */
@Slf4j
public class JShellCodeExecutor {
    
    /**
     * 执行代码
     * 
     * @param context 执行上下文
     * @return 执行结果的 Mono
     */
    public Mono<String> execute(CodeExecutionContext context) {
        return Mono.fromCallable(() -> executeSync(context))
                .timeout(Duration.ofSeconds(context.getTimeout()))
                .onErrorResume(TimeoutException.class, e -> {
                    String error = String.format("Code execution timed out after %d seconds", context.getTimeout());
                    log.error(error);
                    return Mono.just(error);
                })
                .onErrorResume(e -> {
                    String error = "Code execution failed: " + e.getMessage();
                    log.error(error, e);
                    return Mono.just(error);
                });
    }
    
    /**
     * 同步执行代码
     */
    private String executeSync(CodeExecutionContext context) {
        if (context.isLogExecutionDetails()) {
            log.info("JShellCodeExecutor: Starting code execution, code length: {} chars", 
                    context.getCode().length());
        }
        
        // 创建 JShell 实例
        JShell jshell = null;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        
        try {
            // 创建 JShell 配置
            // 使用本地执行引擎，共享主应用的类加载器
            jshell = JShell.builder()
                    .out(new PrintStream(outputStream))
                    .err(new PrintStream(errorStream))
                    .executionEngine(new LocalExecutionControlProvider(), Map.of())
                    .build();
            
            // 创建 ToolBridge 实例
            ToolBridge toolBridge = new ToolBridge(
                    context.getToolRegistry(),
                    context.getAllowedTools(),
                    context.isLogExecutionDetails()
            );
            
            // 注入 ToolBridge 到 JShell 环境
            injectToolBridge(jshell, toolBridge);
            
            // 执行代码
            String result = executeCode(jshell, context.getCode(), context.isLogExecutionDetails());
            
            // 获取输出
            String output = outputStream.toString();
            String error = errorStream.toString();
            
            if (context.isLogExecutionDetails()) {
                log.info("JShellCodeExecutor: Code execution completed");
                if (!output.isEmpty()) {
                    log.debug("JShellCodeExecutor: Output: {}", output);
                }
                if (!error.isEmpty()) {
                    log.warn("JShellCodeExecutor: Error output: {}", error);
                }
            }
            
            // 如果有错误输出，返回错误
            if (!error.isEmpty()) {
                return "Error: " + error;
            }
            
            // 返回结果：优先返回表达式值，其次是 output，最后是空字符串
            if (result != null && !result.isEmpty()) {
                return result;
            } else if (!output.isEmpty()) {
                return output;
            } else {
                // 如果没有任何输出，返回空字符串而不是 null
                log.debug("JShellCodeExecutor: No result or output, returning empty string");
                return "";
            }
            
        } catch (Exception e) {
            log.error("JShellCodeExecutor: Unexpected error during code execution", e);
            throw new RuntimeException("Code execution failed: " + e.getMessage(), e);
        } finally {
            // 清理
            ToolBridgeHolder.clear();
            
            // 关闭 JShell 实例
            if (jshell != null) {
                jshell.close();
            }
        }
    }
    
    /**
     * 注入 ToolBridge 到 JShell 环境
     */
    private void injectToolBridge(JShell jshell, ToolBridge toolBridge) {
        // 导入必要的类
        jshell.eval("import java.util.*;");
        jshell.eval("import java.util.stream.*;");
        
        // 存储 ToolBridge 到静态持有器
        // 由于使用 LocalExecutionControlProvider，JShell 共享主应用的类加载器
        // 因此静态变量可以正常工作
        ToolBridgeHolder.set(toolBridge);
        
        // 注入辅助方法，直接调用 ToolBridgeHolder（使用 jimi2 的包名）
        String helperCode = """
            // 工具调用辅助方法
            String callTool(String toolName, String arguments) {
                try {
                    io.leavesfly.jimi.adk.tools.extended.meta.ToolBridge bridge = io.leavesfly.jimi.adk.tools.extended.meta.ToolBridgeHolder.get();
                    if (bridge == null) {
                        return "Error: ToolBridge not available";
                    }
                    return bridge.callTool(toolName, arguments);
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
            """;
        
        List<SnippetEvent> events = jshell.eval(helperCode);
        for (SnippetEvent event : events) {
            if (event.status() != Snippet.Status.VALID) {
                log.warn("JShellCodeExecutor: Failed to inject helper method: {}", event.exception());
            }
        }
    }
    
    /**
     * 执行代码并返回结果
     * 
     * 将用户代码包装在一个方法中执行，这样 return 语句才能正常工作
     */
    private String executeCode(JShell jshell, String code, boolean logDetails) {
        // 将用户代码包装在一个方法中，这样 return 语句才能正常工作
        String wrappedCode = wrapCodeInMethod(code);
        
        if (logDetails) {
            log.debug("JShellCodeExecutor: Wrapped code:\n{}", wrappedCode);
        }
        
        // 先定义方法
        List<SnippetEvent> defineEvents = jshell.eval(wrappedCode);
        for (SnippetEvent event : defineEvents) {
            if (logDetails) {
                log.debug("JShellCodeExecutor: Define snippet status: {}, kind: {}", 
                        event.status(), event.snippet().kind());
            }
            
            // 检查是否有异常
            if (event.exception() != null) {
                String error = "Exception: " + event.exception().getMessage();
                log.error("JShellCodeExecutor: {}", error);
                return error;
            }
            
            // 检查诊断信息（编译错误）
            jshell.diagnostics(event.snippet()).forEach(diag -> {
                if (diag.isError()) {
                    log.error("JShellCodeExecutor: Compilation error: {}", diag.getMessage(null));
                }
            });
            
            // 如果方法定义失败
            if (event.status() != Snippet.Status.VALID) {
                String error = "Failed to define execution method: " + event.status();
                log.error("JShellCodeExecutor: {}", error);
                return "Error: " + error;
            }
        }
        
        // 调用方法获取结果
        String callCode = "__executeUserCode__()";
        List<SnippetEvent> callEvents = jshell.eval(callCode);
        
        StringBuilder result = new StringBuilder();
        for (SnippetEvent event : callEvents) {
            if (logDetails) {
                log.debug("JShellCodeExecutor: Call snippet status: {}, kind: {}", 
                        event.status(), event.snippet().kind());
            }
            
            // 检查是否有异常
            if (event.exception() != null) {
                String error = "Exception: " + event.exception().getMessage();
                log.error("JShellCodeExecutor: {}", error);
                return error;
            }
            
            // 获取返回值
            if (event.value() != null && !event.value().isEmpty()) {
                result.append(event.value());
                if (logDetails) {
                    log.debug("JShellCodeExecutor: Return value: {}", event.value());
                }
            }
        }
        
        // 处理返回值：去掉 JShell 字符串的引号
        String finalResult = result.toString();
        if (finalResult.startsWith("\"") && finalResult.endsWith("\"")) {
            finalResult = finalResult.substring(1, finalResult.length() - 1);
            // 处理转义字符
            finalResult = finalResult.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t");
        }
        
        return finalResult;
    }
    
    /**
     * 将用户代码包装在一个方法中
     */
    private String wrapCodeInMethod(String code) {
        // 检查代码是否已经包含 return 语句
        boolean hasReturn = code.contains("return ");
        
        StringBuilder wrapped = new StringBuilder();
        wrapped.append("String __executeUserCode__() {\n");
        
        if (hasReturn) {
            // 用户代码已有 return，直接包装
            wrapped.append(code);
        } else {
            // 没有 return，将最后一个表达式作为返回值
            // 简单处理：在最后添加 return
            String trimmedCode = code.trim();
            if (trimmedCode.endsWith(";")) {
                // 如果最后是语句，尝试将其转换为返回值
                wrapped.append(code);
                wrapped.append("\nreturn \"\";\n");
            } else {
                // 最后是表达式，返回它
                wrapped.append("return String.valueOf(").append(code).append(");\n");
            }
        }
        
        wrapped.append("}");
        return wrapped.toString();
    }
    
    /**
     * 验证代码安全性（基础检查）
     * 
     * @param code 要检查的代码
     * @return 如果代码安全返回 true
     */
    public static boolean validateCodeSafety(String code) {
        // 基础安全检查
        String[] dangerousPatterns = {
                "System.exit",
                "Runtime.getRuntime",
                "ProcessBuilder",
                "Runtime.exec",
                "Thread.stop",
                "System.setSecurityManager"
        };
        
        for (String pattern : dangerousPatterns) {
            if (code.contains(pattern)) {
                log.warn("JShellCodeExecutor: Dangerous pattern detected: {}", pattern);
                return false;
            }
        }
        
        return true;
    }
}
