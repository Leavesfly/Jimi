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
 * 任务历史记录
 * 记录每次执行的完整任务信息，用于回答"最近做了什么"等查询
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskHistory {
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("timestamp")
    private Instant timestamp;
    
    @JsonProperty("userQuery")
    private String userQuery;
    
    @JsonProperty("summary")
    private String summary;
    
    @JsonProperty("toolsUsed")
    @Builder.Default
    private List<String> toolsUsed = new ArrayList<>();
    
    @JsonProperty("resultStatus")
    @Builder.Default
    private String resultStatus = "success";
    
    @JsonProperty("stepsCount")
    @Builder.Default
    private int stepsCount = 0;
    
    @JsonProperty("tokensUsed")
    @Builder.Default
    private int tokensUsed = 0;
    
    @JsonProperty("durationMs")
    @Builder.Default
    private long durationMs = 0;
    
    @JsonProperty("tags")
    @Builder.Default
    private List<String> tags = new ArrayList<>();
    
    public void addToolUsed(String toolName) {
        if (!toolsUsed.contains(toolName)) {
            toolsUsed.add(toolName);
        }
    }
    
    public void addTag(String tag) {
        if (!tags.contains(tag)) {
            tags.add(tag);
        }
    }
    
    public String formatForDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s】", 
                timestamp.toString().substring(0, 19).replace('T', ' ')));
        sb.append(" ").append(userQuery);
        if (summary != null && !summary.isEmpty()) {
            sb.append("\n  摘要: ").append(summary);
        }
        if (!toolsUsed.isEmpty()) {
            sb.append("\n  使用工具: ").append(String.join(", ", toolsUsed));
        }
        sb.append(String.format("\n  统计: %d步, %d tokens, 耗时%dms", 
                stepsCount, tokensUsed, durationMs));
        return sb.toString();
    }
}
