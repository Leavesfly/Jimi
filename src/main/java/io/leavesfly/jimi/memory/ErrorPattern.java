package io.leavesfly.jimi.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 错误模式
 * 记录遇到的错误及其解决方案，用于避免重复犯错
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorPattern {
    
    /**
     * 唯一标识
     */
    @JsonProperty("id")
    private String id;
    
    /**
     * 错误类型/分类
     */
    @JsonProperty("errorType")
    private String errorType;
    
    /**
     * 错误消息/关键特征
     */
    @JsonProperty("errorMessage")
    private String errorMessage;
    
    /**
     * 发生场景/上下文
     */
    @JsonProperty("context")
    private String context;
    
    /**
     * 根本原因分析
     */
    @JsonProperty("rootCause")
    private String rootCause;
    
    /**
     * 解决方案
     */
    @JsonProperty("solution")
    private String solution;
    
    /**
     * 出现次数
     */
    @JsonProperty("occurrenceCount")
    @Builder.Default
    private int occurrenceCount = 1;
    
    /**
     * 解决成功次数
     */
    @JsonProperty("resolvedCount")
    @Builder.Default
    private int resolvedCount = 0;
    
    /**
     * 首次出现时间
     */
    @JsonProperty("firstSeen")
    private Instant firstSeen;
    
    /**
     * 最近出现时间
     */
    @JsonProperty("lastSeen")
    private Instant lastSeen;
    
    /**
     * 关联的工具名称
     */
    @JsonProperty("toolName")
    private String toolName;
    
    /**
     * 严重程度（high/medium/low）
     */
    @JsonProperty("severity")
    @Builder.Default
    private String severity = "medium";
    
    /**
     * 增加出现次数
     */
    public void incrementOccurrence() {
        occurrenceCount++;
        lastSeen = Instant.now();
    }
    
    /**
     * 记录解决成功
     */
    public void recordResolution() {
        resolvedCount++;
    }
    
    /**
     * 获取解决成功率
     */
    public double getResolutionRate() {
        if (occurrenceCount == 0) {
            return 0.0;
        }
        return (double) resolvedCount / occurrenceCount;
    }
    
    /**
     * 检查是否匹配给定的错误
     */
    public boolean matches(String errorMsg, String ctx) {
        if (errorMsg == null) {
            return false;
        }
        
        // 简单匹配：错误消息包含关键特征
        boolean msgMatch = errorMessage != null && 
                           errorMsg.toLowerCase().contains(errorMessage.toLowerCase());
        
        // 如果有上下文，也检查上下文
        if (ctx != null && context != null) {
            boolean ctxMatch = ctx.toLowerCase().contains(context.toLowerCase());
            return msgMatch && ctxMatch;
        }
        
        return msgMatch;
    }
    
    /**
     * 格式化为用户友好的描述
     */
    public String formatForDisplay() {
        StringBuilder sb = new StringBuilder();
        
        sb.append(String.format("【%s】 %s\n", errorType, errorMessage));
        
        if (context != null && !context.isEmpty()) {
            sb.append("  场景: ").append(context).append("\n");
        }
        
        if (rootCause != null && !rootCause.isEmpty()) {
            sb.append("  原因: ").append(rootCause).append("\n");
        }
        
        if (solution != null && !solution.isEmpty()) {
            sb.append("  解决方案: ").append(solution).append("\n");
        }
        
        sb.append(String.format("  统计: 出现%d次, 解决%d次 (%.0f%%)\n", 
                occurrenceCount, resolvedCount, getResolutionRate() * 100));
        
        return sb.toString();
    }
    
    /**
     * 生成简短提示（用于注入上下文）
     */
    public String toWarningTip() {
        return String.format("⚠️ 注意: 曾遇到 [%s] 问题。建议: %s", 
                errorType, 
                solution != null ? solution : "请谨慎处理");
    }
}
