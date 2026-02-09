package io.leavesfly.jimi.adk.core.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.adk.api.context.ContextRepository;
import io.leavesfly.jimi.adk.api.message.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 基于 JSONL 的上下文仓库实现
 * 
 * 每行存储一个 JSON 对象：
 * - 消息记录: {"type": "message", "data": {...}}
 * - Token 记录: {"type": "token", "count": 100}
 */
public class JsonlContextRepository implements ContextRepository {
    
    private final Path filePath;
    private final ObjectMapper objectMapper;
    
    public JsonlContextRepository(Path filePath, ObjectMapper objectMapper) {
        this.filePath = filePath;
        this.objectMapper = objectMapper;
    }
    
    @Override
    public RestoredContext restore() {
        if (!Files.exists(filePath)) {
            return new RestoredContext(List.of(), 0);
        }
        
        List<Message> messages = new ArrayList<>();
        int tokenCount = 0;
        
        try (Stream<String> lines = Files.lines(filePath)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                
                RecordWrapper wrapper = objectMapper.readValue(line, RecordWrapper.class);
                
                if ("message".equals(wrapper.type)) {
                    Message message = objectMapper.convertValue(wrapper.data, Message.class);
                    messages.add(message);
                } else if ("token".equals(wrapper.type)) {
                    tokenCount = (int) wrapper.data;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to restore context", e);
        }
        
        return new RestoredContext(messages, tokenCount);
    }
    
    @Override
    public void appendMessages(List<Message> messages) {
        try {
            Files.createDirectories(filePath.getParent());
            
            for (Message message : messages) {
                RecordWrapper wrapper = new RecordWrapper("message", message);
                String json = objectMapper.writeValueAsString(wrapper) + "\n";
                Files.writeString(filePath, json, 
                    StandardOpenOption.CREATE, 
                    StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to append messages", e);
        }
    }
    
    @Override
    public void updateTokenCount(int tokenCount) {
        try {
            Files.createDirectories(filePath.getParent());
            
            RecordWrapper wrapper = new RecordWrapper("token", tokenCount);
            String json = objectMapper.writeValueAsString(wrapper) + "\n";
            Files.writeString(filePath, json,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to update token count", e);
        }
    }
    
    @Override
    public void clear() {
        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear context", e);
        }
    }
    
    /**
     * JSONL 记录包装器
     */
    private static class RecordWrapper {
        public String type;
        public Object data;
        
        public RecordWrapper() {}
        
        public RecordWrapper(String type, Object data) {
            this.type = type;
            this.data = data;
        }
    }
}
