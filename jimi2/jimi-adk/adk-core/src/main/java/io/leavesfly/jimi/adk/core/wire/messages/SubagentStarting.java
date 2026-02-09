package io.leavesfly.jimi.adk.core.wire.messages;

import io.leavesfly.jimi.adk.api.wire.WireMessage;
import lombok.Getter;

/**
 * 子 Agent 启动消息
 * 当 TaskTool 启动子 Agent 时发送
 */
@Getter
public class SubagentStarting extends WireMessage {
    
    /**
     * 子 Agent 名称
     */
    private final String agentName;
    
    /**
     * 子 Agent 描述/提示
     */
    private final String prompt;
    
    public SubagentStarting(String agentName, String prompt) {
        super();
        this.agentName = agentName;
        this.prompt = prompt;
    }
}
