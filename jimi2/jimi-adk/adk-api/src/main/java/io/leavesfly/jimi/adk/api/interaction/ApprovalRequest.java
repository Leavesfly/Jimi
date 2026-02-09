package io.leavesfly.jimi.adk.api.interaction;

/**
 * 审批请求
 */
public class ApprovalRequest {
    
    private final String toolCallId;
    private final String action;
    private final String description;
    
    public ApprovalRequest(String toolCallId, String action, String description) {
        this.toolCallId = toolCallId;
        this.action = action;
        this.description = description;
    }
    
    public String getToolCallId() {
        return toolCallId;
    }
    
    public String getAction() {
        return action;
    }
    
    public String getDescription() {
        return description;
    }
}
