package io.leavesfly.jimi.memory;

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
    
    /**
     * 会话唯一标识
     */
    @JsonProperty("sessionId")
    private String sessionId;
    
    /**
     * 会话开始时间
     */
    @JsonProperty("startTime")
    private Instant startTime;
    
    /**
     * 会话结束时间
     */
    @JsonProperty("endTime")
    private Instant endTime;
    
    /**
     * 会话目标/主题（从首条用户消息提取）
     */
    @JsonProperty("goal")
    private String goal;
    
    /**
     * 最终结果/结论
     */
    @JsonProperty("outcome")
    private String outcome;
    
    /**
     * 关键决策列表
     */
    @JsonProperty("keyDecisions")
    @Builder.Default
    private List<String> keyDecisions = new ArrayList<>();
    
    /**
     * 修改的文件列表
     */
    @JsonProperty("filesModified")
    @Builder.Default
    private List<String> filesModified = new ArrayList<>();
    
    /**
     * 完成的任务数
     */
    @JsonProperty("tasksCompleted")
    @Builder.Default
    private int tasksCompleted = 0;
    
    /**
     * 总步数
     */
    @JsonProperty("totalSteps")
    @Builder.Default
    private int totalSteps = 0;
    
    /**
     * 总Token消耗
     */
    @JsonProperty("totalTokens")
    @Builder.Default
    private int totalTokens = 0;
    
    /**
     * 会话状态（completed/interrupted/error）
     */
    @JsonProperty("status")
    @Builder.Default
    private String status = "completed";
    
    /**
     * 经验教训（从会话中学到的）
     */
    @JsonProperty("lessonsLearned")
    @Builder.Default
    private List<String> lessonsLearned = new ArrayList<>();
    
    /**
     * 添加关键决策
     */
    public void addKeyDecision(String decision) {
        if (decision != null && !decision.isEmpty() && !keyDecisions.contains(decision)) {
            keyDecisions.add(decision);
        }
    }
    
    /**
     * 添加修改的文件
     */
    public void addFileModified(String filePath) {
        if (filePath != null && !filePath.isEmpty() && !filesModified.contains(filePath)) {
            filesModified.add(filePath);
        }
    }
    
    /**
     * 添加经验教训
     */
    public void addLessonLearned(String lesson) {
        if (lesson != null && !lesson.isEmpty() && !lessonsLearned.contains(lesson)) {
            lessonsLearned.add(lesson);
        }
    }
    
    /**
     * 计算会话时长（毫秒）
     */
    public long getDurationMs() {
        if (startTime == null || endTime == null) {
            return 0;
        }
        return endTime.toEpochMilli() - startTime.toEpochMilli();
    }
    
    /**
     * 格式化为用户友好的描述
     */
    public String formatForDisplay() {
        StringBuilder sb = new StringBuilder();
        
        // 时间和目标
        String startTimeStr = startTime != null 
                ? startTime.toString().substring(0, 19).replace('T', ' ') 
                : "未知";
        sb.append(String.format("【%s】 %s\n", startTimeStr, goal != null ? goal : "无目标"));
        
        // 结果
        if (outcome != null && !outcome.isEmpty()) {
            sb.append("  结果: ").append(outcome).append("\n");
        }
        
        // 关键决策
        if (!keyDecisions.isEmpty()) {
            sb.append("  关键决策:\n");
            for (String decision : keyDecisions) {
                sb.append("    • ").append(decision).append("\n");
            }
        }
        
        // 修改的文件
        if (!filesModified.isEmpty()) {
            sb.append("  修改文件: ").append(String.join(", ", filesModified)).append("\n");
        }
        
        // 统计
        long durationSec = getDurationMs() / 1000;
        sb.append(String.format("  统计: %d步, %d tasks, %d tokens, 耗时%ds\n", 
                totalSteps, tasksCompleted, totalTokens, durationSec));
        
        // 经验教训
        if (!lessonsLearned.isEmpty()) {
            sb.append("  经验教训:\n");
            for (String lesson : lessonsLearned) {
                sb.append("    → ").append(lesson).append("\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 生成简短摘要（用于注入上下文）
     */
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
