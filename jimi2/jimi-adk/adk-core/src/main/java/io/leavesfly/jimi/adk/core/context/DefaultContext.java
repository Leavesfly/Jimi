package io.leavesfly.jimi.adk.core.context;

import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.message.Message;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上下文默认实现
 * 管理对话历史和检查点
 */
@Slf4j
public class DefaultContext implements Context {
    
    /**
     * 对话历史
     */
    private final List<Message> history;
    
    /**
     * 检查点缓存（检查点ID -> 历史快照）
     */
    private final Map<Integer, List<Message>> checkpoints;
    
    /**
     * Token 计数
     */
    private int tokenCount;
    
    public DefaultContext() {
        this.history = Collections.synchronizedList(new ArrayList<>());
        this.checkpoints = new ConcurrentHashMap<>();
        this.tokenCount = 0;
    }
    
    /**
     * 从历史消息创建上下文
     */
    public DefaultContext(List<Message> initialHistory) {
        this();
        if (initialHistory != null) {
            this.history.addAll(initialHistory);
        }
    }
    
    @Override
    public List<Message> getHistory() {
        return new ArrayList<>(history);
    }
    
    @Override
    public void addMessage(Message message) {
        if (message != null) {
            history.add(message);
            log.debug("添加消息: role={}, 历史长度={}", message.getRole(), history.size());
        }
    }
    
    @Override
    public void addMessages(List<Message> messages) {
        if (messages != null && !messages.isEmpty()) {
            history.addAll(messages);
            log.debug("添加 {} 条消息, 历史长度={}", messages.size(), history.size());
        }
    }
    
    @Override
    public int getTokenCount() {
        return tokenCount;
    }
    
    @Override
    public void setTokenCount(int count) {
        this.tokenCount = count;
    }
    
    @Override
    public void createCheckpoint(int checkpointId) {
        List<Message> snapshot = new ArrayList<>(history);
        checkpoints.put(checkpointId, snapshot);
        log.debug("创建检查点 {}: {} 条消息", checkpointId, snapshot.size());
    }
    
    @Override
    public boolean restoreCheckpoint(int checkpointId) {
        List<Message> snapshot = checkpoints.get(checkpointId);
        if (snapshot == null) {
            log.warn("检查点 {} 不存在", checkpointId);
            return false;
        }
        
        history.clear();
        history.addAll(snapshot);
        log.debug("恢复检查点 {}: {} 条消息", checkpointId, history.size());
        return true;
    }
    
    @Override
    public void clear() {
        history.clear();
        checkpoints.clear();
        tokenCount = 0;
        log.debug("上下文已清空");
    }
    
    @Override
    public void replaceHistory(List<Message> messages) {
        history.clear();
        if (messages != null) {
            history.addAll(messages);
        }
        log.debug("替换对话历史: {} 条消息", history.size());
    }
}
