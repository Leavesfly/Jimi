package io.leavesfly.jimi.adk.core.engine.async;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
 */
@Slf4j
public class AsyncSubagentPersistence {
    
    private static final String ASYNC_DIR = ".jimi/async_subagents";
    private static final String INDEX_FILE = "index.json";
    private static final String RESULTS_DIR = "results";
    private static final int MAX_HISTORY_SIZE = 100;
    
    private final ObjectMapper objectMapper;
    
    public AsyncSubagentPersistence(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    public AsyncSubagentPersistence() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper = om;
    }
    
    /**
     * 保存子代理记录
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
            Path asyncDir = workDir.resolve(ASYNC_DIR);
            Path resultsDir = asyncDir.resolve(RESULTS_DIR);
            Files.createDirectories(resultsDir);
            
            Path resultFile = resultsDir.resolve(record.getId() + ".json");
            objectMapper.writeValue(resultFile.toFile(), record);
            log.debug("Saved async subagent result: {}", resultFile);
            
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
        
        List<IndexEntry> index = loadIndex(indexPath);
        
        index.removeIf(entry -> entry.getId().equals(newRecord.getId()));
        index.add(0, IndexEntry.fromRecord(newRecord));
        
        if (index.size() > MAX_HISTORY_SIZE) {
            List<IndexEntry> toRemove = index.subList(MAX_HISTORY_SIZE, index.size());
            for (IndexEntry entry : toRemove) {
                Path resultFile = asyncDir.resolve(RESULTS_DIR).resolve(entry.getId() + ".json");
                Files.deleteIfExists(resultFile);
            }
            index = new ArrayList<>(index.subList(0, MAX_HISTORY_SIZE));
        }
        
        objectMapper.writeValue(indexPath.toFile(), index);
        log.debug("Updated async subagent index with {} entries", index.size());
    }
    
    /**
     * 加载索引文件
     */
    private List<IndexEntry> loadIndex(Path indexPath) {
        if (!Files.exists(indexPath)) {
            return new ArrayList<>();
        }
        
        try {
            return objectMapper.readValue(
                    indexPath.toFile(),
                    new TypeReference<List<IndexEntry>>() {}
            );
        } catch (IOException e) {
            log.warn("Failed to load index file, creating new one", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 获取历史记录列表
     */
    public List<AsyncSubagentRecord> getHistory(Path workDir, int limit) {
        if (workDir == null) {
            return List.of();
        }
        
        try {
            Path asyncDir = workDir.resolve(ASYNC_DIR);
            Path indexPath = asyncDir.resolve(INDEX_FILE);
            
            List<IndexEntry> index = loadIndex(indexPath);
            
            List<AsyncSubagentRecord> records = new ArrayList<>();
            int count = 0;
            for (IndexEntry entry : index) {
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
            return Optional.empty();
        }
    }
    
    /**
     * 清理历史记录
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
            
            Path indexPath = asyncDir.resolve(INDEX_FILE);
            List<IndexEntry> index = loadIndex(indexPath);
            int count = index.size();
            
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
            List<IndexEntry> index = loadIndex(indexPath);
            return index.size();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * 索引条目（简化版，不含完整结果）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexEntry {
        
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
        
        public static IndexEntry fromRecord(AsyncSubagentRecord record) {
            return IndexEntry.builder()
                    .id(record.getId())
                    .name(record.getName())
                    .status(record.getStatus())
                    .startTime(record.getStartTime())
                    .durationMs(record.getDurationMs())
                    .build();
        }
    }
}
