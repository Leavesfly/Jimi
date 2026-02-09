package io.leavesfly.jimi.adk.core.wire.messages;

import io.leavesfly.jimi.adk.api.wire.WireMessage;

/**
 * 步骤中断消息
 * 当执行被用户中断时发送
 */
public class StepInterrupted extends WireMessage {
    
    public StepInterrupted() {
        super();
    }
}
