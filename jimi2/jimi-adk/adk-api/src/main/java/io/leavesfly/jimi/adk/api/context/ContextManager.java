package io.leavesfly.jimi.adk.api.context;

import io.leavesfly.jimi.adk.api.message.Message;

import java.util.List;

/**
 * Context 管理器接口
 * 
 * @deprecated 使用 {@link Context} 作为基础上下文接口，
 *             使用 {@link PersistableContext} 获取持久化恢复能力。
 *             此接口与 Context 职责重叠，将在后续版本中移除。
 */
@Deprecated
public interface ContextManager {
    
    /**
     * 恢复上下文
     * 
     * @return 是否成功恢复
     */
    boolean restore();
    
    /**
     * 追加消息到上下文
     * 
     * @param message 消息
     */
    void appendMessage(Message message);
    
    /**
     * 追加多条消息到上下文
     * 
     * @param messages 消息列表
     */
    void appendMessages(List<Message> messages);
    
    /**
     * 获取消息历史（只读）
     * 
     * @return 消息列表
     */
    List<Message> getHistory();
    
    /**
     * 更新 Token 计数
     * 
     * @param count Token 计数
     */
    void updateTokenCount(int count);
    
    /**
     * 获取 Token 计数
     * 
     * @return Token 计数
     */
    int getTokenCount();
    
    /**
     * 清空上下文
     */
    void clear();
}
