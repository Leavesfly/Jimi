package io.leavesfly.jimi.adk.core.interaction;

import io.leavesfly.jimi.adk.api.interaction.Approval;
import io.leavesfly.jimi.adk.api.interaction.ApprovalResponse;
import io.leavesfly.jimi.adk.api.interaction.HumanInputResponse;
import io.leavesfly.jimi.adk.api.interaction.HumanInteraction;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审批服务默认实现
 * <p>
 * 功能：
 * - YOLO 模式（自动批准所有请求）
 * - 会话级批准缓存（批准后同类操作不再询问）
 * - 通过 HumanInteraction 接口向用户请求审批
 */
@Slf4j
public class DefaultApproval implements Approval {

    /**
     * YOLO 模式（自动批准所有请求）
     */
    private final boolean yolo;

    /**
     * 会话级批准缓存
     * 存储已被批准的操作类型，同一会话内不再重复询问
     */
    private final Set<String> sessionApprovals;

    /**
     * 人机交互接口（可选）
     * 用于向用户展示审批请求并获取响应
     */
    private final HumanInteraction humanInteraction;

    /**
     * 创建审批服务
     *
     * @param yolo             是否启用 YOLO 模式
     * @param humanInteraction 人机交互接口（YOLO 模式下可为 null）
     */
    public DefaultApproval(boolean yolo, HumanInteraction humanInteraction) {
        this.yolo = yolo;
        this.humanInteraction = humanInteraction;
        this.sessionApprovals = ConcurrentHashMap.newKeySet();
    }

    /**
     * 创建 YOLO 模式审批服务
     */
    public static DefaultApproval yoloMode() {
        return new DefaultApproval(true, null);
    }

    /**
     * 创建交互模式审批服务
     */
    public static DefaultApproval interactive(HumanInteraction humanInteraction) {
        return new DefaultApproval(false, humanInteraction);
    }

    @Override
    public ApprovalResponse requestApproval(String toolCallId, String action, String description) {
        // YOLO 模式直接批准
        if (yolo) {
            log.debug("YOLO mode: auto-approving action: {}", action);
            return ApprovalResponse.APPROVE;
        }

        // 检查会话级缓存
        if (sessionApprovals.contains(action)) {
            log.debug("Action '{}' already approved for session", action);
            return ApprovalResponse.APPROVE;
        }

        // 如果没有人机交互接口，默认批准
        if (humanInteraction == null) {
            log.warn("No HumanInteraction available, auto-approving action: {}", action);
            return ApprovalResponse.APPROVE;
        }

        // 通过 HumanInteraction 请求用户确认
        try {
            String prompt = formatApprovalPrompt(action, description);
            HumanInputResponse response = humanInteraction.requestConfirmation(prompt);

            if (response.isApproved()) {
                log.info("Action '{}' approved by user", action);
                return ApprovalResponse.APPROVE;
            } else {
                log.info("Action '{}' rejected by user", action);
                return ApprovalResponse.REJECT;
            }
        } catch (Exception e) {
            log.error("Failed to request approval for action '{}', rejecting", action, e);
            return ApprovalResponse.REJECT;
        }
    }

    @Override
    public void clearSessionApprovals() {
        sessionApprovals.clear();
        log.info("Session approvals cleared");
    }

    @Override
    public int getSessionApprovalCount() {
        return sessionApprovals.size();
    }

    @Override
    public boolean isYolo() {
        return yolo;
    }

    /**
     * 添加会话级批准
     *
     * @param action 操作类型
     */
    public void addSessionApproval(String action) {
        sessionApprovals.add(action);
        log.debug("Added session approval for action: {}", action);
    }

    /**
     * 格式化审批提示
     */
    private String formatApprovalPrompt(String action, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 需要审批操作:\n");
        sb.append("  操作: ").append(action).append("\n");
        if (description != null && !description.isEmpty()) {
            sb.append("  描述: ").append(description).append("\n");
        }
        sb.append("是否批准？");
        return sb.toString();
    }
}
