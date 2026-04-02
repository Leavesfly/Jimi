package io.leavesfly.jimi.wire.message;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Teammate 状态更新事件
 * 通知 UI 层某个 Teammate 的任务执行状态变化
 */
@Data
@NoArgsConstructor
public class TeammateStatusUpdate implements WireMessage {

    /**
     * 团队 ID
     */
    private String teamId;

    /**
     * Teammate ID
     */
    private String teammateId;

    /**
     * 任务 ID
     */
    private String taskId;

    /**
     * 状态描述
     */
    private String status;

    /**
     * 详细信息
     */
    private String detail;

    public TeammateStatusUpdate(String teamId, String teammateId,
                                 String taskId, String status, String detail) {
        this.teamId = teamId;
        this.teammateId = teammateId;
        this.taskId = taskId;
        this.status = status;
        this.detail = detail;
    }

    @Override
    public String getMessageType() {
        return "TeammateStatusUpdate";
    }
}
