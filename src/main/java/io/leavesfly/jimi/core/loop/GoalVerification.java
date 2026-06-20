package io.leavesfly.jimi.core.loop;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Goal 验证结果
 * <p>
 * 由独立的验证者模型返回，表示目标条件是否已满足。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalVerification {

    /**
     * 目标条件是否已满足
     */
    private boolean satisfied;

    /**
     * 验证理由（说明为什么满足/不满足）
     */
    private String reason;

    /**
     * 创建"已满足"结果
     */
    public static GoalVerification satisfied(String reason) {
        return GoalVerification.builder().satisfied(true).reason(reason).build();
    }

    /**
     * 创建"未满足"结果
     */
    public static GoalVerification notSatisfied(String reason) {
        return GoalVerification.builder().satisfied(false).reason(reason).build();
    }
}
