package io.leavesfly.jimi.adk.api.engine;

import io.leavesfly.jimi.adk.api.interaction.Approval;
import io.leavesfly.jimi.adk.api.interaction.HumanInteraction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 交互上下文 - 聚合人机交互相关的协作者
 * <p>
 * 将 HumanInteraction 和 Approval 等交互关注点集中管理，
 * 使 Runtime 不再直接持有这些字段。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InteractionContext {

    /**
     * 人机交互接口（可选）
     */
    private HumanInteraction humanInteraction;

    /**
     * 审批服务（可选）
     */
    private Approval approval;

    /**
     * 是否处于 YOLO 模式（快捷判断）
     */
    public boolean isYoloMode() {
        return approval != null && approval.isYolo();
    }
}
