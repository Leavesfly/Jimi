package io.leavesfly.jimi.soul.context;

import io.leavesfly.jimi.llm.message.Message;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Context 持久化仓库接口
 * 
 * 职责：
 * - 封装所有文件 I/O 操作
 * - 提供统一的持久化抽象
 * - 支持不同的存储实现（JSONL、数据库等）
 * 
 * 设计原则：
 * - 单一职责：仅负责数据持久化
 * - 依赖倒置：Context 依赖接口而非具体实现
 * - 开闭原则：可扩展新的存储实现
 */
public interface ContextRepository {
    
    /**
     * 从存储中恢复上下文
     * 
     * @return 恢复的数据，包含消息历史、Token计数、检查点ID
     */
    Mono<RestoredContext> restore();
    
    /**
     * 追加消息到存储
     * 
     * @param messages 要持久化的消息列表
     */
    Mono<Void> appendMessages(List<Message> messages);
    
    /**
     * 更新 Token 计数
     * 
     * @param tokenCount Token 计数
     */
    Mono<Void> updateTokenCount(int tokenCount);
    
    /**
     * 创建检查点
     * 
     * @param checkpointId 检查点 ID
     */
    Mono<Void> saveCheckpoint(int checkpointId);
    
    /**
     * 回退到指定检查点
     * 
     * @param checkpointId 目标检查点 ID
     * @return 回退后的上下文数据
     */
    Mono<RestoredContext> revertToCheckpoint(int checkpointId);
    
    /**
     * 恢复的上下文数据
     */
    class RestoredContext {
        private final List<Message> messages;
        private final int tokenCount;
        private final int nextCheckpointId;
        
        public RestoredContext(List<Message> messages, int tokenCount, int nextCheckpointId) {
            this.messages = messages;
            this.tokenCount = tokenCount;
            this.nextCheckpointId = nextCheckpointId;
        }
        
        public List<Message> getMessages() {
            return messages;
        }
        
        public int getTokenCount() {
            return tokenCount;
        }
        
        public int getNextCheckpointId() {
            return nextCheckpointId;
        }
    }
}
