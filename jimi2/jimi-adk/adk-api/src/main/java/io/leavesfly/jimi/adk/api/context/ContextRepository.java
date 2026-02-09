package io.leavesfly.jimi.adk.api.context;

import io.leavesfly.jimi.adk.api.message.Message;

import java.util.List;

/**
 * Context 持久化仓库接口
 * 
 * 职责：
 * - 封装所有文件 I/O 操作
 * - 提供统一的持久化抽象
 * - 支持不同的存储实现（JSONL、数据库等）
 */
public interface ContextRepository {
    
    /**
     * 从存储中恢复上下文
     * 
     * @return 恢复的数据，包含消息历史、Token计数
     */
    RestoredContext restore();
    
    /**
     * 追加消息到存储
     * 
     * @param messages 要持久化的消息列表
     */
    void appendMessages(List<Message> messages);
    
    /**
     * 更新 Token 计数
     * 
     * @param tokenCount Token 计数
     */
    void updateTokenCount(int tokenCount);
    
    /**
     * 清空上下文
     */
    void clear();
    
    /**
     * 恢复的上下文数据
     */
    class RestoredContext {
        private final List<Message> messages;
        private final int tokenCount;
        
        public RestoredContext(List<Message> messages, int tokenCount) {
            this.messages = messages;
            this.tokenCount = tokenCount;
        }
        
        public List<Message> getMessages() {
            return messages;
        }
        
        public int getTokenCount() {
            return tokenCount;
        }
    }
}
