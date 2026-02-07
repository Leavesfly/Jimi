package io.leavesfly.jimi.adk.core.wire.messages;

import io.leavesfly.jimi.adk.api.wire.WireMessage;
import lombok.Getter;

/**
 * 步骤结束消息
 */
@Getter
public class StepEnd extends WireMessage {
    
    /**
     * 步骤编号
     */
    private final int stepNumber;
    
    /**
     * 是否有工具调用
     */
    private final boolean hasToolCalls;
    
    public StepEnd(int stepNumber, boolean hasToolCalls) {
        super();
        this.stepNumber = stepNumber;
        this.hasToolCalls = hasToolCalls;
    }
}
