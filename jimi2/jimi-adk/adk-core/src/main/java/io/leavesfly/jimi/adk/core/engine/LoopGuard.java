package io.leavesfly.jimi.adk.core.engine;

import lombok.extern.slf4j.Slf4j;

/**
 * 循环守卫
 * <p>
 * 职责：检测 Agent 主循环中的异常模式（如连续无工具调用），
 * 在需要时强制终止循环，防止无限思考。
 * </p>
 */
@Slf4j
public class LoopGuard {

    /** 连续无工具调用步数 */
    private int consecutiveNoToolCallSteps = 0;

    /**
     * 重置计数器（当有工具调用时调用）
     */
    public void reset() {
        if (consecutiveNoToolCallSteps > 0) {
            log.debug("重置连续思考计数器 (之前: {})", consecutiveNoToolCallSteps);
        }
        consecutiveNoToolCallSteps = 0;
    }

    /**
     * 递增无工具调用步数，检查是否应强制完成
     *
     * @param maxThinkingSteps 最大连续思考步数
     * @return true 如果应该强制完成
     */
    public boolean shouldForceComplete(int maxThinkingSteps) {
        consecutiveNoToolCallSteps++;

        if (consecutiveNoToolCallSteps >= maxThinkingSteps) {
            log.warn("连续思考 {} 步未调用工具，强制完成", consecutiveNoToolCallSteps);
            return true;
        }

        log.debug("连续思考步数: {}/{}", consecutiveNoToolCallSteps, maxThinkingSteps);
        return false;
    }

    /**
     * 获取当前连续无工具调用步数
     */
    public int getConsecutiveSteps() {
        return consecutiveNoToolCallSteps;
    }
}
