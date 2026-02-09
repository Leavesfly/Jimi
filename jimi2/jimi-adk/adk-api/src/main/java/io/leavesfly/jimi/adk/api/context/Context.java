package io.leavesfly.jimi.adk.api.context;

import io.leavesfly.jimi.adk.api.message.Message;

import java.util.List;

/**
 * 上下文接口
 * 管理对话历史和状态
 */
public interface Context {
    
    /**
     * 获取对话历史
     *
     * @return 消息列表
     */
    List<Message> getHistory();
    
    /**
     * 添加消息到历史
     *
     * @param message 消息
     */
    void addMessage(Message message);
    
    /**
     * 添加多条消息到历史
     *
     * @param messages 消息列表
     */
    void addMessages(List<Message> messages);
    
    /**
     * 获取 Token 计数
     *
     * @return Token 数量
     */
    int getTokenCount();
    
    /**
     * 设置 Token 计数
     *
     * @param count Token 数量
     */
    void setTokenCount(int count);
    
    /**
     * 创建检查点
     *
     * @param checkpointId 检查点 ID
     */
    void createCheckpoint(int checkpointId);
    
    /**
     * 恢复到检查点
     *
     * @param checkpointId 检查点 ID
     * @return 是否恢复成功
     */
    boolean restoreCheckpoint(int checkpointId);
    
    /**
     * 清空历史
     */
    void clear();
    
    /**
     * 替换整个对话历史（用于上下文压缩）
     *
     * @param messages 新的消息列表
     */
    void replaceHistory(List<Message> messages);
    
    /**
     * 获取最后一条消息
     *
     * @return 最后一条消息，如果没有则返回 null
     */
    default Message getLastMessage() {
        List<Message> history = getHistory();
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }
}
