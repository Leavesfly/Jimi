package io.leavesfly.jimi.wire.message;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Subagent 启动事件
 * 用于通知父 AgentExecutor 进行上下文入栈
 */
@Data
@NoArgsConstructor
public class SubagentStarting implements WireMessage {
    
    /**
     * 子 Agent 名称
     */
    private String subagentName;
    
    /**
     * 传递给 Subagent 的提示词
     */
    private String prompt;
    
    /**
     * 构造函数
     * 
     * @param subagentName 子 Agent 名称
     * @param prompt 提示词
     */
    public SubagentStarting(String subagentName, String prompt) {
        this.subagentName = subagentName;
        this.prompt = prompt;
    }
    
    @Override
    public String getMessageType() {
        return "SubagentStarting";
    }
}
