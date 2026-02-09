package io.leavesfly.jimi.adk.api.exception;

/**
 * 最大步数达到异常
 * 当 Agent 循环达到最大步数限制时抛出
 */
public class MaxStepsReachedException extends AdkException {
    
    private final int maxSteps;
    
    public MaxStepsReachedException(int maxSteps) {
        super(String.format("Max steps reached: %d", maxSteps));
        this.maxSteps = maxSteps;
    }
    
    public int getMaxSteps() {
        return maxSteps;
    }
}
