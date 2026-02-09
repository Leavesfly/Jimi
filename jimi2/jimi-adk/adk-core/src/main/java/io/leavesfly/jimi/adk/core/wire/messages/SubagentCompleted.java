package io.leavesfly.jimi.adk.core.wire.messages;

import io.leavesfly.jimi.adk.api.wire.WireMessage;
import lombok.Getter;

/**
 * 子 Agent 完成消息
 * 当子 Agent 执行完成时发送
 */
@Getter
public class SubagentCompleted extends WireMessage {
    
    /**
     * 子 Agent 名称
     */
    private final String agentName;
    
    /**
     * 是否成功
     */
    private final boolean success;
    
    /**
     * 结果摘要
     */
    private final String summary;
    
    /**
     * 执行步数
     */
    private final int steps;
    
    /**
     * Token 消耗
     */
    private final int tokensUsed;
    
    public SubagentCompleted(String agentName, boolean success, String summary,
                            int steps, int tokensUsed) {
        super();
        this.agentName = agentName;
        this.success = success;
        this.summary = summary;
        this.steps = steps;
        this.tokensUsed = tokensUsed;
    }
    
    /**
     * 创建成功完成消息
     */
    public static SubagentCompleted success(String agentName, String summary,
                                           int steps, int tokensUsed) {
        return new SubagentCompleted(agentName, true, summary, steps, tokensUsed);
    }
    
    /**
     * 创建失败完成消息
     */
    public static SubagentCompleted failure(String agentName, String error) {
        return new SubagentCompleted(agentName, false, error, 0, 0);
    }
}
