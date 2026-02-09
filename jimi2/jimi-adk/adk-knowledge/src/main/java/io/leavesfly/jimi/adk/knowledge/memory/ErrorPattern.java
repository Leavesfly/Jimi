package io.leavesfly.jimi.adk.knowledge.memory;

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
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("errorType")
    private String errorType;
    
    @JsonProperty("errorMessage")
    private String errorMessage;
    
    @JsonProperty("context")
    private String context;
    
    @JsonProperty("rootCause")
    private String rootCause;
    
    @JsonProperty("solution")
    private String solution;
    
    @JsonProperty("occurrenceCount")
    @Builder.Default
    private int occurrenceCount = 1;
    
    @JsonProperty("resolvedCount")
    @Builder.Default
    private int resolvedCount = 0;
    
    @JsonProperty("firstSeen")
    private Instant firstSeen;
    
    @JsonProperty("lastSeen")
    private Instant lastSeen;
    
    @JsonProperty("toolName")
    private String toolName;
    
    @JsonProperty("severity")
    @Builder.Default
    private String severity = "medium";
    
    public void incrementOccurrence() {
        occurrenceCount++;
        lastSeen = Instant.now();
    }
    
    public void recordResolution() {
        resolvedCount++;
    }
    
    public double getResolutionRate() {
        if (occurrenceCount == 0) {
            return 0.0;
        }
        return (double) resolvedCount / occurrenceCount;
    }
    
    public boolean matches(String errorMsg, String ctx) {
        if (errorMsg == null) {
            return false;
        }
        boolean msgMatch = errorMessage != null && 
                           errorMsg.toLowerCase().contains(errorMessage.toLowerCase());
        if (ctx != null && context != null) {
            boolean ctxMatch = ctx.toLowerCase().contains(context.toLowerCase());
            return msgMatch && ctxMatch;
        }
        return msgMatch;
    }
    
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
    
    public String toWarningTip() {
        return String.format("⚠️ 注意: 曾遇到 [%s] 问题。建议: %s", 
                errorType, 
                solution != null ? solution : "请谨慎处理");
    }
}
