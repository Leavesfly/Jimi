package io.leavesfly.jimi.adk.core.wire.messages;

import io.leavesfly.jimi.adk.api.wire.WireMessage;
import lombok.Getter;

/**
 * Token 使用量消息
 * 用于通知 UI 层当前步骤的 Token 消耗
 */
@Getter
public class TokenUsageMessage extends WireMessage {
    
    /**
     * 输入 Token 数
     */
    private final int inputTokens;
    
    /**
     * 输出 Token 数
     */
    private final int outputTokens;
    
    /**
     * 总 Token 数
     */
    private final int totalTokens;
    
    /**
     * 累计总 Token 数（当前会话）
     */
    private final int cumulativeTokens;
    
    public TokenUsageMessage(int inputTokens, int outputTokens, int totalTokens, int cumulativeTokens) {
        super();
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.cumulativeTokens = cumulativeTokens;
    }
}
