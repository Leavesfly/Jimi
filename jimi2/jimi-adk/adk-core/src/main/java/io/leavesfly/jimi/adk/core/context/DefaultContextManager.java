package io.leavesfly.jimi.adk.core.context;

import io.leavesfly.jimi.adk.api.context.ContextManager;
import io.leavesfly.jimi.adk.api.context.ContextRepository;
import io.leavesfly.jimi.adk.api.message.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 默认上下文管理器实现
 * 
 * 负责维护对话历史、Token 计数和持久化
 */
public class DefaultContextManager implements ContextManager {
    
    private final ContextRepository repository;
    private final List<Message> history;
    private int tokenCount;
    
    public DefaultContextManager(ContextRepository repository) {
        this.repository = repository;
        this.history = new ArrayList<>();
        this.tokenCount = 0;
    }
    
    @Override
    public boolean restore() {
        try {
            ContextRepository.RestoredContext restoredContext = repository.restore();
            if (restoredContext != null) {
                history.clear();
                history.addAll(restoredContext.getMessages());
                this.tokenCount = restoredContext.getTokenCount();
                return !history.isEmpty();
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public void appendMessage(Message message) {
        history.add(message);
        repository.appendMessages(Collections.singletonList(message));
    }
    
    @Override
    public void appendMessages(List<Message> messages) {
        history.addAll(messages);
        repository.appendMessages(messages);
    }
    
    @Override
    public List<Message> getHistory() {
        return Collections.unmodifiableList(history);
    }
    
    @Override
    public void updateTokenCount(int count) {
        this.tokenCount = count;
        repository.updateTokenCount(count);
    }
    
    @Override
    public int getTokenCount() {
        return tokenCount;
    }
    
    @Override
    public void clear() {
        history.clear();
        tokenCount = 0;
        repository.clear();
    }
}
