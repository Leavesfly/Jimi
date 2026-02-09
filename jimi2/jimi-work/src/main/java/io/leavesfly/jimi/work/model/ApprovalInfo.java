package io.leavesfly.jimi.work.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审批信息 - 封装审批请求和响应
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApprovalInfo {

    /** 工具调用 ID */
    private String toolCallId;
    /** 请求的操作 */
    private String action;
    /** 操作描述 */
    private String description;

    /**
     * 审批响应
     */
    public enum Response {
        APPROVE,          // 允许一次
        APPROVE_SESSION,  // 本次会话允许
        REJECT            // 拒绝
    }
}
