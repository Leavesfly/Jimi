package io.leavesfly.jimi.soul.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.soul.context.ContextRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * JSONL 格式的 Context 持久化实现
 * 
 * 存储格式：
 * - 每行一个 JSON 对象
 * - 消息对象：标准的 Message JSON
 * - 元数据对象：{"role": "_usage", "token_count": 1000}
 * - 检查点对象：{"role": "_checkpoint", "id": 1}
 * 
 * 特性：
 * - 轮转备份：回退时保留原文件为 .1, .2 等
 * - 原子性：使用文件移动保证操作原子性
 * - 追加写入：高效的顺序写入
 */
@Slf4j
public class JSONLContextRepository implements ContextRepository {
    
    private final Path fileBackend;
    private final ObjectMapper objectMapper;
    
    public JSONLContextRepository(Path fileBackend, ObjectMapper objectMapper) {
        this.fileBackend = fileBackend;
        this.objectMapper = objectMapper;
    }
    
    @Override
    public Mono<RestoredContext> restore() {
        return Mono.fromCallable(() -> {
            log.debug("Restoring context from file: {}", fileBackend);
            
            if (!Files.exists(fileBackend)) {
                log.debug("No context file found, returning empty context");
                return new RestoredContext(new ArrayList<>(), 0, 0);
            }
            
            if (Files.size(fileBackend) == 0) {
                log.debug("Empty context file, returning empty context");
                return new RestoredContext(new ArrayList<>(), 0, 0);
            }
            
            List<Message> messages = new ArrayList<>();
            int tokenCount = 0;
            int nextCheckpointId = 0;
            
            try (BufferedReader reader = Files.newBufferedReader(fileBackend)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    
                    ObjectNode lineJson = objectMapper.readValue(line, ObjectNode.class);
                    String role = lineJson.get("role").asText();
                    
                    // 处理元数据
                    if ("_usage".equals(role)) {
                        tokenCount = lineJson.get("token_count").asInt();
                        continue;
                    }
                    
                    if ("_checkpoint".equals(role)) {
                        nextCheckpointId = lineJson.get("id").asInt() + 1;
                        continue;
                    }
                    
                    // 解析为普通消息
                    Message message = objectMapper.readValue(line, Message.class);
                    messages.add(message);
                }
            }
            
            log.info("Restored context: {} messages, {} tokens, {} checkpoints", 
                    messages.size(), tokenCount, nextCheckpointId);
            
            return new RestoredContext(messages, tokenCount, nextCheckpointId);
        });
    }
    
    @Override
    public Mono<Void> appendMessages(List<Message> messages) {
        return Mono.fromRunnable(() -> {
            try (BufferedWriter writer = Files.newBufferedWriter(fileBackend, 
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (Message message : messages) {
                    String json = objectMapper.writeValueAsString(message);
                    writer.write(json);
                    writer.newLine();
                }
            } catch (IOException e) {
                log.error("Failed to persist messages to file", e);
                throw new RuntimeException("Failed to persist messages", e);
            }
        });
    }
    
    @Override
    public Mono<Void> updateTokenCount(int tokenCount) {
        return Mono.fromRunnable(() -> {
            try (BufferedWriter writer = Files.newBufferedWriter(fileBackend,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                ObjectNode usageNode = objectMapper.createObjectNode();
                usageNode.put("role", "_usage");
                usageNode.put("token_count", tokenCount);
                writer.write(objectMapper.writeValueAsString(usageNode));
                writer.newLine();
            } catch (IOException e) {
                log.error("Failed to persist token count", e);
                throw new RuntimeException("Failed to persist token count", e);
            }
        });
    }
    
    @Override
    public Mono<Void> saveCheckpoint(int checkpointId) {
        return Mono.fromRunnable(() -> {
            try (BufferedWriter writer = Files.newBufferedWriter(fileBackend,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                ObjectNode checkpointNode = objectMapper.createObjectNode();
                checkpointNode.put("role", "_checkpoint");
                checkpointNode.put("id", checkpointId);
                writer.write(objectMapper.writeValueAsString(checkpointNode));
                writer.newLine();
            } catch (IOException e) {
                log.error("Failed to persist checkpoint", e);
                throw new RuntimeException("Failed to persist checkpoint", e);
            }
        });
    }
    
    @Override
    public Mono<RestoredContext> revertToCheckpoint(int checkpointId) {
        return Mono.fromCallable(() -> {
            log.debug("Reverting to checkpoint: {}", checkpointId);
            
            try {
                // 轮转历史文件
                Path rotatedPath = getNextRotationPath();
                Files.move(fileBackend, rotatedPath, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Rotated history file: {}", rotatedPath);
                
                List<Message> messages = new ArrayList<>();
                int tokenCount = 0;
                int nextCheckpointId = 0;
                
                // 从轮转文件恢复到指定检查点
                try (BufferedReader reader = Files.newBufferedReader(rotatedPath);
                     BufferedWriter writer = Files.newBufferedWriter(fileBackend,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) {
                            continue;
                        }
                        
                        ObjectNode lineJson = objectMapper.readValue(line, ObjectNode.class);
                        String role = lineJson.get("role").asText();
                        
                        // 遇到目标检查点时停止
                        if ("_checkpoint".equals(role) && lineJson.get("id").asInt() == checkpointId) {
                            break;
                        }
                        
                        // 写入新文件
                        writer.write(line);
                        writer.newLine();
                        
                        // 恢复到内存
                        if ("_usage".equals(role)) {
                            tokenCount = lineJson.get("token_count").asInt();
                        } else if ("_checkpoint".equals(role)) {
                            nextCheckpointId = lineJson.get("id").asInt() + 1;
                        } else {
                            Message message = objectMapper.readValue(line, Message.class);
                            messages.add(message);
                        }
                    }
                }
                
                log.info("Reverted to checkpoint {}: {} messages, {} tokens", 
                        checkpointId, messages.size(), tokenCount);
                
                return new RestoredContext(messages, tokenCount, nextCheckpointId);
                
            } catch (IOException e) {
                log.error("Failed to revert to checkpoint", e);
                throw new RuntimeException("Failed to revert to checkpoint", e);
            }
        });
    }
    
    /**
     * 获取下一个可用的轮转文件路径
     */
    private Path getNextRotationPath() throws IOException {
        Path parent = fileBackend.getParent();
        String baseName = fileBackend.getFileName().toString();
        
        for (int i = 1; i < 1000; i++) {
            Path rotatedPath = parent.resolve(baseName + "." + i);
            if (!Files.exists(rotatedPath)) {
                return rotatedPath;
            }
        }
        
        throw new IOException("No available rotation path found");
    }
}
