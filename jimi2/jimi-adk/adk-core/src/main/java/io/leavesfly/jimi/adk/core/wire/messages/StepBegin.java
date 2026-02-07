package io.leavesfly.jimi.adk.core.wire.messages;

import io.leavesfly.jimi.adk.api.wire.WireMessage;
import lombok.Getter;

/**
 * 步骤开始消息
 */
@Getter
public class StepBegin extends WireMessage {
    
    /**
     * 步骤编号
     */
    private final int stepNumber;
    
    /**
     * 是否为子代理
     */
    private final boolean subagent;
    
    /**
     * 代理名称
     */
    private final String agentName;
    
    public StepBegin(int stepNumber) {
        this(stepNumber, false, null);
    }
    
    public StepBegin(int stepNumber, boolean subagent, String agentName) {
        super();
        this.stepNumber = stepNumber;
        this.subagent = subagent;
        this.agentName = agentName;
    }
}
