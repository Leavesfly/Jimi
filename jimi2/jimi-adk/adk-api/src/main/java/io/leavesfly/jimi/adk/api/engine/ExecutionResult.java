package io.leavesfly.jimi.adk.api.engine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行结果 - Agent 执行的返回结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionResult {
    
    /**
     * 执行是否成功
     */
    private boolean success;
    
    /**
     * 最终响应文本
     */
    private String response;
    
    /**
     * 执行的步数
     */
    private int stepsExecuted;
    
    /**
     * 使用的总 Token 数
     */
    private int tokensUsed;
    
    /**
     * 错误信息（如果有）
     */
    private String error;
    
    /**
     * 执行是否被中断
     */
    private boolean interrupted;
    
    /**
     * 创建成功结果
     */
    public static ExecutionResult success(String response, int steps, int tokens) {
        return ExecutionResult.builder()
                .success(true)
                .response(response)
                .stepsExecuted(steps)
                .tokensUsed(tokens)
                .build();
    }
    
    /**
     * 创建错误结果
     */
    public static ExecutionResult error(String error) {
        return ExecutionResult.builder()
                .success(false)
                .error(error)
                .build();
    }
    
    /**
     * 创建中断结果
     */
    public static ExecutionResult interrupted() {
        return ExecutionResult.builder()
                .success(false)
                .interrupted(true)
                .build();
    }
}
