package io.leavesfly.jimi.adk.api.interaction;

/**
 * 审批响应
 */
public enum ApprovalResponse {
    /** 批准单次请求 */
    APPROVE,
    /** 批准当前会话的所有同类请求 */
    APPROVE_FOR_SESSION,
    /** 拒绝请求 */
    REJECT
}
