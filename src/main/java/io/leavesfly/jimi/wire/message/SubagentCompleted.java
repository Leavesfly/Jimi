package io.leavesfly.jimi.wire.message;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Subagent 完成事件
 * 用于通知父 AgentExecutor 进行上下文恢复
 */
@Data
@NoArgsConstructor
public class SubagentCompleted implements WireMessage {
    
    /**
     * 子任务完成摘要
     */
    private String summary;
    
    /**
     * 构造函数
     * 
     * @param summary 完成摘要
     */
    public SubagentCompleted(String summary) {
        this.summary = summary;
    }
    
    @Override
    public String getMessageType() {
        return "SubagentCompleted";
    }
}
