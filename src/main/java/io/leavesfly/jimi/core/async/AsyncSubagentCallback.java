package io.leavesfly.jimi.core.async;

/**
 * 异步子代理完成回调接口
 * 
 * @author Jimi
 */
@FunctionalInterface
public interface AsyncSubagentCallback {
    
    /**
     * 子代理完成时的回调
     * 无论成功、失败还是超时，都会触发此回调
     * 
     * @param subagent 完成的子代理实例
     */
    void onComplete(AsyncSubagent subagent);
}
