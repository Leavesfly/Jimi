package io.leavesfly.jimi.core.async;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 异步子代理持久化服务
 * 负责将已完成的子代理记录持久化到磁盘
 * 
 * 存储结构：
 * ${workDir}/.jimi/async_subagents/
 * ├── index.json          # 索引文件
 * └── results/
 *     ├── abc123.json     # 单个结果
 *     └── def456.json
 * 
 * @author Jimi
 */
@Slf4j
@Service
public class AsyncSubagentPersistence {
    
    private static final String ASYNC_DIR = ".jimi/async_subagents";
    private static final String INDEX_FILE = "index.json";
    private static final String RESULTS_DIR = "results";
    private static final int MAX_HISTORY_SIZE = 100;  // 最多保留的历史记录数
    
    private final ObjectMapper objectMapper;
    
    public AsyncSubagentPersistence() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }
    
    /**
     * 保存子代理记录
     * 
     * @param workDir 工作目录
     * @param subagent 子代理实例
     */
    public void save(Path workDir, AsyncSubagent subagent) {
        if (workDir == null || subagent == null) {
            log.debug("Cannot save: workDir or subagent is null");
            return;
        }
        
        try {
            AsyncSubagentRecord record = AsyncSubagentRecord.fromSubagent(subagent);
            save(workDir, record);
        } catch (Exception e) {
            log.warn("Failed to save async subagent record: {}", subagent.getId(), e);
        }
    }
    
    /**
     * 保存记录
     */
    public void save(Path workDir, AsyncSubagentRecord record) {
        if (workDir == null || record == null) {
            log.debug("Cannot save: workDir or record is null");
            return;
        }
        
        try {
            // 确保目录存在
            Path asyncDir = workDir.resolve(ASYNC_DIR);
            Path resultsDir = asyncDir.resolve(RESULTS_DIR);
            Files.createDirectories(resultsDir);
            
            // 保存单个结果文件
            Path resultFile = resultsDir.resolve(record.getId() + ".json");
            objectMapper.writeValue(resultFile.toFile(), record);
            log.debug("Saved async subagent result: {}", resultFile);
            
            // 更新索引
            updateIndex(asyncDir, record);
            
        } catch (IOException e) {
            log.warn("Failed to save async subagent record: {}", record.getId(), e);
        }
    }
    
    /**
     * 更新索引文件
     */
    private void updateIndex(Path asyncDir, AsyncSubagentRecord newRecord) throws IOException {
        Path indexPath = asyncDir.resolve(INDEX_FILE);
        
        // 读取现有索引
        List<AsyncSubagentIndexEntry> index = loadIndex(indexPath);
        
        // 添加新记录（如果已存在则更新）
        index.removeIf(entry -> entry.getId().equals(newRecord.getId()));
        index.add(0, AsyncSubagentIndexEntry.fromRecord(newRecord));  // 添加到开头
        
        // 限制大小
        if (index.size() > MAX_HISTORY_SIZE) {
            // 删除多余的结果文件
            List<AsyncSubagentIndexEntry> toRemove = index.subList(MAX_HISTORY_SIZE, index.size());
            for (AsyncSubagentIndexEntry entry : toRemove) {
                Path resultFile = asyncDir.resolve(RESULTS_DIR).resolve(entry.getId() + ".json");
                Files.deleteIfExists(resultFile);
            }
            index = new ArrayList<>(index.subList(0, MAX_HISTORY_SIZE));
        }
        
        // 保存索引
        objectMapper.writeValue(indexPath.toFile(), index);
        log.debug("Updated async subagent index with {} entries", index.size());
    }
    
    /**
     * 加载索引文件
     */
    private List<AsyncSubagentIndexEntry> loadIndex(Path indexPath) {
        if (!Files.exists(indexPath)) {
            return new ArrayList<>();
        }
        
        try {
            return objectMapper.readValue(
                    indexPath.toFile(),
                    new TypeReference<List<AsyncSubagentIndexEntry>>() {}
            );
        } catch (IOException e) {
            log.warn("Failed to load index file, creating new one", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 获取历史记录列表
     * 
     * @param workDir 工作目录
     * @param limit 限制数量
     * @return 历史记录列表
     */
    public List<AsyncSubagentRecord> getHistory(Path workDir, int limit) {
        if (workDir == null) {
            return List.of();
        }
        
        try {
            Path asyncDir = workDir.resolve(ASYNC_DIR);
            Path indexPath = asyncDir.resolve(INDEX_FILE);
            
            List<AsyncSubagentIndexEntry> index = loadIndex(indexPath);
            
            // 获取详细记录
            List<AsyncSubagentRecord> records = new ArrayList<>();
            int count = 0;
            for (AsyncSubagentIndexEntry entry : index) {
                if (count >= limit) break;
                
                Optional<AsyncSubagentRecord> record = loadRecord(workDir, entry.getId());
                record.ifPresent(records::add);
                count++;
            }
            
            return records;
            
        } catch (Exception e) {
            log.warn("Failed to load history", e);
            return List.of();
        }
    }
    
    /**
     * 加载单个记录
     */
    public Optional<AsyncSubagentRecord> loadRecord(Path workDir, String id) {
        if (workDir == null || id == null) {
            return Optional.empty();
        }
        
        try {
            Path asyncDir = workDir.resolve(ASYNC_DIR);
            Path resultFile = asyncDir.resolve(RESULTS_DIR).resolve(id + ".json");
            
            if (!Files.exists(resultFile)) {
                return Optional.empty();
            }
            
            AsyncSubagentRecord record = objectMapper.readValue(resultFile.toFile(), AsyncSubagentRecord.class);
            return Optional.of(record);
            
        } catch (IOException e) {
            log.warn("Failed to load record: {} - {}", id, e.getMessage());
            log.debug("Full exception", e);
            return Optional.empty();
        }
    }
    
    /**
     * 清理历史记录
     * 
     * @param workDir 工作目录
     * @return 清理的记录数
     */
    public int clearHistory(Path workDir) {
        if (workDir == null) {
            return 0;
        }
        
        try {
            Path asyncDir = workDir.resolve(ASYNC_DIR);
            
            if (!Files.exists(asyncDir)) {
                return 0;
            }
            
            // 读取索引获取记录数
            Path indexPath = asyncDir.resolve(INDEX_FILE);
            List<AsyncSubagentIndexEntry> index = loadIndex(indexPath);
            int count = index.size();
            
            // 删除结果目录
            Path resultsDir = asyncDir.resolve(RESULTS_DIR);
            if (Files.exists(resultsDir)) {
                Files.walk(resultsDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                log.warn("Failed to delete: {}", path, e);
                            }
                        });
            }
            
            // 删除索引文件
            Files.deleteIfExists(indexPath);
            
            log.info("Cleared {} async subagent history records", count);
            return count;
            
        } catch (IOException e) {
            log.warn("Failed to clear history", e);
            return 0;
        }
    }
    
    /**
     * 获取历史记录数量
     */
    public int getHistoryCount(Path workDir) {
        if (workDir == null) {
            return 0;
        }
        
        try {
            Path indexPath = workDir.resolve(ASYNC_DIR).resolve(INDEX_FILE);
            List<AsyncSubagentIndexEntry> index = loadIndex(indexPath);
            return index.size();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * 索引条目（简化版，不含完整结果）
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AsyncSubagentIndexEntry {
        
        @JsonProperty("id")
        private String id;
        
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("status")
        private String status;
        
        @JsonProperty("start_time")
        private Instant startTime;
        
        @JsonProperty("duration_ms")
        private long durationMs;
        
        public static AsyncSubagentIndexEntry fromRecord(AsyncSubagentRecord record) {
            return AsyncSubagentIndexEntry.builder()
                    .id(record.getId())
                    .name(record.getName())
                    .status(record.getStatus())
                    .startTime(record.getStartTime())
                    .durationMs(record.getDurationMs())
                    .build();
        }
    }
}
