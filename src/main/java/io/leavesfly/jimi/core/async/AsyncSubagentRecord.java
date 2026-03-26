package io.leavesfly.jimi.core.async;

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
 * 
 * @author Jimi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncSubagentRecord {
    
    /**
     * 子代理唯一标识
     */
    @JsonProperty("id")
    private String id;
    
    /**
     * 子代理名称
     */
    @JsonProperty("name")
    private String name;
    
    /**
     * 运行模式（fire_and_forget / watch）
     */
    @JsonProperty("mode")
    private String mode;
    
    /**
     * 最终状态
     */
    @JsonProperty("status")
    private String status;
    
    /**
     * 启动时间
     */
    @JsonProperty("start_time")
    private Instant startTime;
    
    /**
     * 结束时间
     */
    @JsonProperty("end_time")
    private Instant endTime;
    
    /**
     * 运行时长（毫秒）
     */
    @JsonProperty("duration_ms")
    private long durationMs;
    
    /**
     * 原始任务提示词
     */
    @JsonProperty("prompt")
    private String prompt;
    
    /**
     * 执行结果（成功时）
     */
    @JsonProperty("result")
    private String result;
    
    /**
     * 错误信息（失败时）
     */
    @JsonProperty("error")
    private String error;
    
    /**
     * 触发模式（Watch 模式）
     */
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
