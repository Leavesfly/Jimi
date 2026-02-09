package io.leavesfly.jimi.adk.api.interaction;

/**
 * 审批服务接口
 * 管理工具执行的用户审批流程
 */
public interface Approval {
    
    /**
     * 请求审批
     * 
     * @param toolCallId 工具调用 ID
     * @param action 操作类型
     * @param description 操作描述
     * @return 审批响应
     */
    ApprovalResponse requestApproval(String toolCallId, String action, String description);
    
    /**
     * 清除会话级批准缓存
     */
    void clearSessionApprovals();
    
    /**
     * 获取会话级批准的操作数量
     * 
     * @return 批准数量
     */
    int getSessionApprovalCount();
    
    /**
     * 检查是否为YOLO模式（自动批准所有请求）
     * 
     * @return 是否为YOLO模式
     */
    boolean isYolo();
}
