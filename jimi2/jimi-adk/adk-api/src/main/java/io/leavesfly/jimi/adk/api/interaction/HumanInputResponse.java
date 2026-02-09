package io.leavesfly.jimi.adk.api.interaction;

/**
 * 人机交互响应
 */
public class HumanInputResponse {
    
    private final Status status;
    private final String content;
    
    public HumanInputResponse(Status status, String content) {
        this.status = status;
        this.content = content;
    }
    
    public Status getStatus() {
        return status;
    }
    
    public String getContent() {
        return content;
    }
    
    /**
     * 响应状态
     */
    public enum Status {
        /** 确认通过/满意 */
        APPROVED,
        /** 拒绝 */
        REJECTED,
        /** 需要修改 */
        NEEDS_MODIFICATION,
        /** 已提供输入 */
        INPUT_PROVIDED
    }
    
    public boolean isApproved() {
        return status == Status.APPROVED;
    }
    
    public boolean needsModification() {
        return status == Status.NEEDS_MODIFICATION;
    }
    
    public boolean isRejected() {
        return status == Status.REJECTED;
    }
    
    public static HumanInputResponse approved() {
        return new HumanInputResponse(Status.APPROVED, null);
    }
    
    public static HumanInputResponse rejected() {
        return new HumanInputResponse(Status.REJECTED, null);
    }
    
    public static HumanInputResponse needsModification(String feedback) {
        return new HumanInputResponse(Status.NEEDS_MODIFICATION, feedback);
    }
    
    public static HumanInputResponse inputProvided(String input) {
        return new HumanInputResponse(Status.INPUT_PROVIDED, input);
    }
}
