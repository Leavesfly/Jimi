package io.leavesfly.jimi.adk.knowledge.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话摘要
 * 记录每次会话的完整信息，用于回答"上次聊了什么"等查询
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionSummary {
    
    @JsonProperty("sessionId")
    private String sessionId;
    
    @JsonProperty("startTime")
    private Instant startTime;
    
    @JsonProperty("endTime")
    private Instant endTime;
    
    @JsonProperty("goal")
    private String goal;
    
    @JsonProperty("outcome")
    private String outcome;
    
    @JsonProperty("keyDecisions")
    @Builder.Default
    private List<String> keyDecisions = new ArrayList<>();
    
    @JsonProperty("filesModified")
    @Builder.Default
    private List<String> filesModified = new ArrayList<>();
    
    @JsonProperty("tasksCompleted")
    @Builder.Default
    private int tasksCompleted = 0;
    
    @JsonProperty("totalSteps")
    @Builder.Default
    private int totalSteps = 0;
    
    @JsonProperty("totalTokens")
    @Builder.Default
    private int totalTokens = 0;
    
    @JsonProperty("status")
    @Builder.Default
    private String status = "completed";
    
    @JsonProperty("lessonsLearned")
    @Builder.Default
    private List<String> lessonsLearned = new ArrayList<>();
    
    public void addKeyDecision(String decision) {
        if (decision != null && !decision.isEmpty() && !keyDecisions.contains(decision)) {
            keyDecisions.add(decision);
        }
    }
    
    public void addFileModified(String filePath) {
        if (filePath != null && !filePath.isEmpty() && !filesModified.contains(filePath)) {
            filesModified.add(filePath);
        }
    }
    
    public void addLessonLearned(String lesson) {
        if (lesson != null && !lesson.isEmpty() && !lessonsLearned.contains(lesson)) {
            lessonsLearned.add(lesson);
        }
    }
    
    public long getDurationMs() {
        if (startTime == null || endTime == null) {
            return 0;
        }
        return endTime.toEpochMilli() - startTime.toEpochMilli();
    }
    
    public String formatForDisplay() {
        StringBuilder sb = new StringBuilder();
        String startTimeStr = startTime != null 
                ? startTime.toString().substring(0, 19).replace('T', ' ') 
                : "未知";
        sb.append(String.format("【%s】 %s\n", startTimeStr, goal != null ? goal : "无目标"));
        if (outcome != null && !outcome.isEmpty()) {
            sb.append("  结果: ").append(outcome).append("\n");
        }
        if (!keyDecisions.isEmpty()) {
            sb.append("  关键决策:\n");
            for (String decision : keyDecisions) {
                sb.append("    • ").append(decision).append("\n");
            }
        }
        if (!filesModified.isEmpty()) {
            sb.append("  修改文件: ").append(String.join(", ", filesModified)).append("\n");
        }
        long durationSec = getDurationMs() / 1000;
        sb.append(String.format("  统计: %d步, %d tasks, %d tokens, 耗时%ds\n", 
                totalSteps, tasksCompleted, totalTokens, durationSec));
        if (!lessonsLearned.isEmpty()) {
            sb.append("  经验教训:\n");
            for (String lesson : lessonsLearned) {
                sb.append("    → ").append(lesson).append("\n");
            }
        }
        return sb.toString();
    }
    
    public String toShortSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(goal != null ? goal : "会话");
        if (outcome != null && !outcome.isEmpty()) {
            sb.append(" → ").append(outcome);
        }
        if (!filesModified.isEmpty()) {
            sb.append(" (修改了 ").append(filesModified.size()).append(" 个文件)");
        }
        return sb.toString();
    }
}
