package io.leavesfly.jimi.wire.message;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Team 完成事件
 * 通知 UI 层团队执行完成
 */
@Data
@NoArgsConstructor
public class TeamCompleted implements WireMessage {

    /**
     * 团队 ID
     */
    private String teamId;

    /**
     * 是否全部成功
     */
    private boolean success;

    /**
     * 结果摘要
     */
    private String summary;

    /**
     * 完成的任务数
     */
    private int completedTasks;

    /**
     * 失败的任务数
     */
    private int failedTasks;

    public TeamCompleted(String teamId, boolean success, String summary,
                         int completedTasks, int failedTasks) {
        this.teamId = teamId;
        this.success = success;
        this.summary = summary;
        this.completedTasks = completedTasks;
        this.failedTasks = failedTasks;
    }

    @Override
    public String getMessageType() {
        return "TeamCompleted";
    }
}
