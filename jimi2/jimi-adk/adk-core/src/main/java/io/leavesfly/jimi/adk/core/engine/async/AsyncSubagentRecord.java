package io.leavesfly.jimi.adk.core.engine.async;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 异步子代理持久化记录
 * 用于将已完成的子代理状态和结果持久化到磁盘
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncSubagentRecord {
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("mode")
    private String mode;
    
    @JsonProperty("status")
    private String status;
    
    @JsonProperty("start_time")
    private Instant startTime;
    
    @JsonProperty("end_time")
    private Instant endTime;
    
    @JsonProperty("duration_ms")
    private long durationMs;
    
    @JsonProperty("prompt")
    private String prompt;
    
    @JsonProperty("result")
    private String result;
    
    @JsonProperty("error")
    private String error;
    
    @JsonProperty("trigger_pattern")
    private String triggerPattern;
    
    /**
     * 从 AsyncSubagent 创建持久化记录
     */
    public static AsyncSubagentRecord fromSubagent(AsyncSubagent subagent) {
        return AsyncSubagentRecord.builder()
                .id(subagent.getId())
                .name(subagent.getName())
                .mode(subagent.getMode() != null ? subagent.getMode().getValue() : null)
                .status(subagent.getStatus() != null ? subagent.getStatus().getValue() : null)
                .startTime(subagent.getStartTime())
                .endTime(subagent.getEndTime())
                .durationMs(subagent.getRunningDuration().toMillis())
                .prompt(subagent.getPrompt())
                .result(subagent.getResult())
                .error(subagent.getError() != null ? subagent.getError().getMessage() : null)
                .triggerPattern(subagent.getTriggerPattern())
                .build();
    }
    
    /**
     * 格式化运行时长
     */
    @JsonIgnore
    public String getFormattedDuration() {
        long seconds = durationMs / 1000;
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            return String.format("%dm%ds", seconds / 60, seconds % 60);
        } else {
            return String.format("%dh%dm%ds", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        }
    }
}
