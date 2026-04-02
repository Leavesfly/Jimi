package io.leavesfly.jimi.wire.message;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Team 启动事件
 * 通知 UI 层团队开始执行
 */
@Data
@NoArgsConstructor
public class TeamStarting implements WireMessage {

    /**
     * 团队 ID
     */
    private String teamId;

    /**
     * 团队成员 ID 列表
     */
    private List<String> teammateIds;

    /**
     * 任务数量
     */
    private int taskCount;

    public TeamStarting(String teamId, List<String> teammateIds, int taskCount) {
        this.teamId = teamId;
        this.teammateIds = teammateIds != null ? new ArrayList<>(teammateIds) : new ArrayList<>();
        this.taskCount = taskCount;
    }

    @Override
    public String getMessageType() {
        return "TeamStarting";
    }
}
