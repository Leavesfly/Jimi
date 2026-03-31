package io.leavesfly.jimi.core.approval;

import io.leavesfly.jimi.wire.message.WireMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.publisher.MonoSink;

/**
 * 审批请求
 * 当工具需要用户确认时发送
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalRequest implements WireMessage {
    
    /**
     * 工具调用 ID
     */
    private String toolCallId;
    
    /**
     * 操作类型（用于会话级缓存）
     */
    private String action;
    
    /**
     * 操作描述
     */
    private String description;
    
    /**
     * 响应处理器（内部使用）
     */
    private transient MonoSink<ApprovalResponse> responseSink;
    
    @Override
    public String getMessageType() {
        return "ApprovalRequest";
    }
    
    /**
     * 解析审批响应
     */
    public void resolve(ApprovalResponse response) {
        if (responseSink != null) {
            responseSink.success(response);
        }
    }
}
