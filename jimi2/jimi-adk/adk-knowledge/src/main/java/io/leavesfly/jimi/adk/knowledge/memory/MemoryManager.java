package io.leavesfly.jimi.adk.knowledge.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.knowledge.rag.VectorStore;
import io.leavesfly.jimi.adk.knowledge.rag.CodeChunk;
import io.leavesfly.jimi.adk.knowledge.rag.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 记忆管理器(精简版)
 * 使用统一的MemoryStore管理所有类型的长期记忆
 * 支持语义向量检索（复用现有 VectorStore 和 EmbeddingProvider）
 */
@Slf4j
public class MemoryManager {
    
    private static final String MEMORY_TYPE = "long_term_memory";
    private static final String STORE_FILENAME = "memory_store.json";
    
    private final ObjectMapper objectMapper;
    private final MemoryConfig config;
    
    // 复用现有的向量检索组件（可选）
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    
    // 统一的记忆存储
    private MemoryStore memoryStore;
    
    private Path memoryDir;
    private boolean initialized = false;
    
    public MemoryManager(
            ObjectMapper objectMapper, 
            MemoryConfig config,
            VectorStore vectorStore,
            EmbeddingProvider embeddingProvider) {
        this.objectMapper = objectMapper;
        this.config = config;
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
        
        if (vectorStore != null && embeddingProvider != null) {
            log.info("记忆管理器启用语义检索能力");
        } else {
            log.info("记忆管理器使用关键词检索模式");
        }
    }
    
    /**
     * 初始化记忆目录（基于工作目录）
     */
    public void initialize(Runtime runtime) {
        if (initialized) {
            return;
        }
        
        Path workDir = runtime.getWorkDir();
        this.memoryDir = workDir.resolve(".jimi").resolve("memory");
        
        try {
            Files.createDirectories(memoryDir);
            log.info("初始化记忆管理器，存储路径: {}", memoryDir);
            
            loadStore().subscribe(
                store -> {
                    this.memoryStore = store;
                    log.info("加载记忆存储完成，总记忆数: {}", store.getTotalCount());
                },
                error -> {
                    log.warn("加载记忆存储失败，创建新存储: {}", error.getMessage());
                    this.memoryStore = createNewStore();
                }
            );
            
            initialized = true;
        } catch (IOException e) {
            log.error("创建记忆目录失败: {}", memoryDir, e);
        }
    }
    
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("MemoryManager未初始化，请先调用initialize()方法");
        }
    }
    
    public boolean isSemanticSearchEnabled() {
        return vectorStore != null && embeddingProvider != null;
    }
    
    // ==================== 用户偏好管理 ====================
    
    public Mono<UserPreferences> loadPreferences() {
        return loadStore()
                .map(store -> {
                    List<MemoryEntry> prefs = store.getByType(MemoryType.USER_PREFERENCE);
                    if (prefs.isEmpty()) {
                        return UserPreferences.getDefault();
                    }
                    return UserPreferences.getDefault();
                })
                .defaultIfEmpty(UserPreferences.getDefault());
    }
    
    public Mono<Void> savePreferences(UserPreferences prefs) {
        return Mono.empty();
    }
    
    // ==================== 项目知识管理 ====================
    
    public Mono<List<ProjectInsight>> queryInsights(String query, int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        if (isSemanticSearchEnabled()) {
            return queryBySemantics(query, limit);
        }
        return queryByKeyword(query, limit);
    }
    
    private Mono<List<ProjectInsight>> queryBySemantics(String query, int topK) {
        return embeddingProvider.embed(query)
                .flatMap(queryVector -> vectorStore.search(queryVector, topK * 2))
                .map(results -> results.stream()
                        .filter(r -> isMemoryChunk(r.getChunk()))
                        .limit(topK)
                        .map(this::convertToInsight)
                        .collect(Collectors.toList()))
                .onErrorResume(e -> {
                    log.warn("语义检索失败，回退到关键词: {}", e.getMessage());
                    return queryByKeyword(query, topK);
                });
    }
    
    private Mono<List<ProjectInsight>> queryByKeyword(String keyword, int limit) {
        return loadStore()
                .map(store -> store.searchByKeyword(MemoryType.PROJECT_INSIGHT, keyword, limit))
                .map(entries -> entries.stream()
                        .map(this::convertEntryToInsight)
                        .collect(Collectors.toList()))
                .defaultIfEmpty(List.of());
    }
    
    public Mono<Void> addInsight(ProjectInsight insight) {
        if (!config.isLongTermEnabled() || !config.isAutoExtract()) {
            return Mono.empty();
        }
        
        MemoryEntry entry = MemoryEntry.builder()
                .id(insight.getId() != null ? insight.getId() : UUID.randomUUID().toString())
                .type(MemoryType.PROJECT_INSIGHT)
                .content(insight.getContent())
                .createdAt(insight.getTimestamp() != null ? insight.getTimestamp() : Instant.now())
                .updatedAt(Instant.now())
                .confidence(insight.getConfidence())
                .accessCount(insight.getAccessCount())
                .lastAccessed(insight.getLastAccessed())
                .build();
        
        entry.setMetadata("category", insight.getCategory());
        entry.setMetadata("source", insight.getSource());
        
        return loadStore()
                .flatMap(store -> {
                    store.add(entry);
                    store.prune(MemoryType.PROJECT_INSIGHT, config.getMaxInsights(), config.getInsightExpiryDays());
                    return saveStore(store);
                })
                .then(addToVectorStore(entry))
                .doOnSuccess(v -> log.debug("添加知识: [{}] {}", insight.getCategory(), 
                        insight.getContent().substring(0, Math.min(50, insight.getContent().length()))));
    }
    
    private Mono<Void> addToVectorStore(MemoryEntry entry) {
        if (!isSemanticSearchEnabled()) {
            return Mono.empty();
        }
        
        return embeddingProvider.embed(entry.getContent())
                .flatMap(vector -> {
                    CodeChunk chunk = CodeChunk.builder()
                            .id(entry.getId())
                            .content(entry.getContent())
                            .embedding(vector)
                            .metadata(Map.of(
                                    "type", MEMORY_TYPE,
                                    "memoryType", entry.getType().toString(),
                                    "category", entry.getMetadataString("category") != null ? entry.getMetadataString("category") : "general",
                                    "timestamp", entry.getCreatedAt().toString()
                            ))
                            .build();
                    return vectorStore.add(chunk);
                })
                .then()
                .onErrorResume(e -> {
                    log.warn("存储到VectorStore失败: {}", e.getMessage());
                    return Mono.empty();
                });
    }
    
    // ==================== 任务历史管理 ====================
    
    public Mono<Void> addTaskHistory(TaskHistory task) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        MemoryEntry entry = convertTaskHistoryToEntry(task);
        return loadStore()
                .flatMap(store -> {
                    store.add(entry);
                    store.prune(MemoryType.TASK_HISTORY, config.getMaxTaskHistory(), config.getInsightExpiryDays());
                    return saveStore(store);
                })
                .doOnSuccess(v -> log.info("记录任务历史: {}", task.getUserQuery()));
    }
    
    public Mono<List<TaskHistory>> getRecentTasks(int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        return loadStore()
                .map(store -> store.getRecent(MemoryType.TASK_HISTORY, limit))
                .map(entries -> entries.stream()
                        .map(this::convertEntryToTaskHistory)
                        .collect(Collectors.toList()))
                .defaultIfEmpty(List.of());
    }
    
    public Mono<List<TaskHistory>> searchTaskHistory(String keyword, int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        return loadStore()
                .map(store -> store.searchByKeyword(MemoryType.TASK_HISTORY, keyword, limit))
                .map(entries -> entries.stream()
                        .map(this::convertEntryToTaskHistory)
                        .collect(Collectors.toList()))
                .defaultIfEmpty(List.of());
    }
    
    public Mono<List<TaskHistory>> getTasksByTimeRange(Instant startTime, Instant endTime) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        return loadStore()
                .map(store -> store.getByTimeRange(MemoryType.TASK_HISTORY, startTime, endTime))
                .map(entries -> entries.stream()
                        .map(this::convertEntryToTaskHistory)
                        .collect(Collectors.toList()))
                .defaultIfEmpty(List.of());
    }
    
    // ==================== 会话摘要管理 ====================
    
    public Mono<Void> addSessionSummary(SessionSummary session) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        MemoryEntry entry = convertSessionSummaryToEntry(session);
        return loadStore()
                .flatMap(store -> {
                    store.add(entry);
                    store.prune(MemoryType.SESSION_SUMMARY, config.getMaxTaskHistory(), config.getInsightExpiryDays());
                    return saveStore(store);
                })
                .doOnSuccess(v -> log.info("记录会话摘要: {}", session.getGoal()));
    }
    
    public Mono<SessionSummary> getLastSession() {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        return loadStore()
                .map(MemoryStore::getLastSession)
                .map(this::convertEntryToSessionSummary);
    }
    
    public Mono<List<SessionSummary>> getRecentSessions(int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        return loadStore()
                .map(store -> store.getRecent(MemoryType.SESSION_SUMMARY, limit))
                .map(entries -> entries.stream()
                        .map(this::convertEntryToSessionSummary)
                        .collect(Collectors.toList()))
                .defaultIfEmpty(List.of());
    }
    
    public Mono<List<SessionSummary>> searchSessions(String keyword, int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        return loadStore()
                .map(store -> store.searchByKeyword(MemoryType.SESSION_SUMMARY, keyword, limit))
                .map(entries -> entries.stream()
                        .map(this::convertEntryToSessionSummary)
                        .collect(Collectors.toList()))
                .defaultIfEmpty(List.of());
    }
    
    // ==================== 错误模式管理 ====================
    
    public Mono<Void> addOrUpdateErrorPattern(ErrorPattern pattern) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        MemoryEntry entry = convertErrorPatternToEntry(pattern);
        return loadStore()
                .flatMap(store -> {
                    store.addOrUpdateErrorPattern(entry);
                    store.prune(MemoryType.ERROR_PATTERN, config.getMaxInsights(), config.getInsightExpiryDays());
                    return saveStore(store);
                })
                .doOnSuccess(v -> log.debug("记录错误模式: {}", pattern.getErrorType()));
    }
    
    public Mono<Void> recordErrorResolution(String errorMessage, String context) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        return loadStore()
                .flatMap(store -> {
                    store.recordErrorResolution(errorMessage, context);
                    return saveStore(store);
                });
    }
    
    public Mono<ErrorPattern> findErrorPattern(String errorMessage, String context) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        return loadStore()
                .flatMap(store -> Mono.justOrEmpty(store.findErrorPattern(errorMessage, context)))
                .map(this::convertEntryToErrorPattern);
    }
    
    public Mono<List<ErrorPattern>> getMostFrequentErrors(int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        return loadStore()
                .map(store -> store.getMostFrequentErrors(limit))
                .map(entries -> entries.stream()
                        .map(this::convertEntryToErrorPattern)
                        .collect(Collectors.toList()))
                .defaultIfEmpty(List.of());
    }
    
    public Mono<List<ErrorPattern>> getErrorsByTool(String toolName, int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        return loadStore()
                .map(store -> store.searchByKeyword(MemoryType.ERROR_PATTERN, toolName, limit))
                .map(entries -> entries.stream()
                        .map(this::convertEntryToErrorPattern)
                        .collect(Collectors.toList()))
                .defaultIfEmpty(List.of());
    }
    
    public Mono<TaskPattern> findPattern(String trigger) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        return loadStore()
                .map(store -> store.searchByKeyword(MemoryType.TASK_PATTERN, trigger, 1))
                .filter(list -> !list.isEmpty())
                .map(list -> convertEntryToTaskPattern(list.get(0)));
    }
    
    public Mono<Void> addPattern(TaskPattern pattern) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        MemoryEntry entry = convertTaskPatternToEntry(pattern);
        return loadStore()
                .flatMap(store -> {
                    store.add(entry);
                    return saveStore(store);
                })
                .doOnSuccess(v -> log.debug("添加任务模式: {}", pattern.getTrigger()));
    }
    
    // ==================== 数据转换方法 ====================
    
    private boolean isMemoryChunk(CodeChunk chunk) {
        if (chunk == null || chunk.getMetadata() == null) return false;
        return MEMORY_TYPE.equals(chunk.getMetadata().get("type"));
    }
    
    private ProjectInsight convertToInsight(VectorStore.SearchResult result) {
        CodeChunk chunk = result.getChunk();
        Map<String, String> metadata = chunk.getMetadata();
        return ProjectInsight.builder()
                .id(chunk.getId())
                .category(metadata != null ? metadata.getOrDefault("category", "general") : "general")
                .content(chunk.getContent())
                .source(metadata != null ? metadata.getOrDefault("source", "unknown") : "unknown")
                .timestamp(parseTimestamp(metadata != null ? metadata.get("timestamp") : null))
                .confidence(result.getScore())
                .accessCount(0)
                .lastAccessed(Instant.now())
                .build();
    }
    
    private ProjectInsight convertEntryToInsight(MemoryEntry entry) {
        return ProjectInsight.builder()
                .id(entry.getId())
                .category(entry.getMetadataString("category"))
                .content(entry.getContent())
                .source(entry.getMetadataString("source"))
                .timestamp(entry.getCreatedAt())
                .confidence(entry.getConfidence())
                .accessCount(entry.getAccessCount())
                .lastAccessed(entry.getLastAccessed())
                .build();
    }
    
    private TaskHistory convertEntryToTaskHistory(MemoryEntry entry) {
        return TaskHistory.builder()
                .id(entry.getId())
                .timestamp(entry.getCreatedAt())
                .userQuery(entry.getMetadataString("userQuery"))
                .summary(entry.getContent())
                .resultStatus(entry.getMetadataString("resultStatus"))
                .stepsCount(entry.getMetadataInt("stepsCount") != null ? entry.getMetadataInt("stepsCount") : 0)
                .tokensUsed(entry.getMetadataInt("tokensUsed") != null ? entry.getMetadataInt("tokensUsed") : 0)
                .durationMs(entry.getMetadataInt("durationMs") != null ? entry.getMetadataInt("durationMs").longValue() : 0L)
                .build();
    }
    
    private MemoryEntry convertTaskHistoryToEntry(TaskHistory task) {
        MemoryEntry entry = MemoryEntry.builder()
                .id(task.getId() != null ? task.getId() : UUID.randomUUID().toString())
                .type(MemoryType.TASK_HISTORY)
                .content(task.getSummary() != null ? task.getSummary() : task.getUserQuery())
                .createdAt(task.getTimestamp() != null ? task.getTimestamp() : Instant.now())
                .updatedAt(Instant.now())
                .build();
        entry.setMetadata("userQuery", task.getUserQuery());
        entry.setMetadata("resultStatus", task.getResultStatus());
        entry.setMetadata("stepsCount", task.getStepsCount());
        entry.setMetadata("tokensUsed", task.getTokensUsed());
        entry.setMetadata("durationMs", task.getDurationMs());
        return entry;
    }
    
    private SessionSummary convertEntryToSessionSummary(MemoryEntry entry) {
        if (entry == null) return null;
        return SessionSummary.builder()
                .sessionId(entry.getId())
                .startTime(entry.getCreatedAt())
                .endTime((Instant) entry.getMetadata("endTime"))
                .goal(entry.getMetadataString("goal"))
                .outcome(entry.getContent())
                .build();
    }
    
    private MemoryEntry convertSessionSummaryToEntry(SessionSummary session) {
        MemoryEntry entry = MemoryEntry.builder()
                .id(session.getSessionId() != null ? session.getSessionId() : UUID.randomUUID().toString())
                .type(MemoryType.SESSION_SUMMARY)
                .content(session.getOutcome() != null ? session.getOutcome() : session.getGoal())
                .createdAt(session.getStartTime() != null ? session.getStartTime() : Instant.now())
                .updatedAt(Instant.now())
                .build();
        entry.setMetadata("goal", session.getGoal());
        entry.setMetadata("endTime", session.getEndTime());
        return entry;
    }
    
    private ErrorPattern convertEntryToErrorPattern(MemoryEntry entry) {
        return ErrorPattern.builder()
                .id(entry.getId())
                .errorType(entry.getMetadataString("errorType"))
                .errorMessage(entry.getMetadataString("errorMessage"))
                .context(entry.getMetadataString("context"))
                .rootCause(entry.getMetadataString("rootCause"))
                .solution(entry.getContent())
                .occurrenceCount(entry.getMetadataInt("occurrenceCount") != null ? entry.getMetadataInt("occurrenceCount") : 1)
                .resolvedCount(entry.getMetadataInt("resolvedCount") != null ? entry.getMetadataInt("resolvedCount") : 0)
                .firstSeen((Instant) entry.getMetadata("firstSeen"))
                .lastSeen(entry.getUpdatedAt())
                .toolName(entry.getMetadataString("toolName"))
                .severity(entry.getMetadataString("severity"))
                .build();
    }
    
    private MemoryEntry convertErrorPatternToEntry(ErrorPattern pattern) {
        MemoryEntry entry = MemoryEntry.builder()
                .id(pattern.getId() != null ? pattern.getId() : UUID.randomUUID().toString())
                .type(MemoryType.ERROR_PATTERN)
                .content(pattern.getSolution() != null ? pattern.getSolution() : pattern.getRootCause())
                .createdAt(pattern.getFirstSeen() != null ? pattern.getFirstSeen() : Instant.now())
                .updatedAt(pattern.getLastSeen() != null ? pattern.getLastSeen() : Instant.now())
                .build();
        entry.setMetadata("errorType", pattern.getErrorType());
        entry.setMetadata("errorMessage", pattern.getErrorMessage());
        entry.setMetadata("context", pattern.getContext());
        entry.setMetadata("rootCause", pattern.getRootCause());
        entry.setMetadata("occurrenceCount", pattern.getOccurrenceCount());
        entry.setMetadata("resolvedCount", pattern.getResolvedCount());
        entry.setMetadata("firstSeen", pattern.getFirstSeen());
        entry.setMetadata("toolName", pattern.getToolName());
        entry.setMetadata("severity", pattern.getSeverity());
        return entry;
    }
    
    private TaskPattern convertEntryToTaskPattern(MemoryEntry entry) {
        return TaskPattern.builder()
                .id(entry.getId())
                .trigger(entry.getMetadataString("trigger"))
                .usageCount(entry.getAccessCount())
                .lastUsed(entry.getLastAccessed())
                .build();
    }
    
    private MemoryEntry convertTaskPatternToEntry(TaskPattern pattern) {
        MemoryEntry entry = MemoryEntry.builder()
                .id(pattern.getId() != null ? pattern.getId() : UUID.randomUUID().toString())
                .type(MemoryType.TASK_PATTERN)
                .content(pattern.getSteps() != null ? String.join("\n", pattern.getSteps()) : "")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .accessCount(pattern.getUsageCount())
                .lastAccessed(pattern.getLastUsed())
                .build();
        entry.setMetadata("trigger", pattern.getTrigger());
        entry.setMetadata("successRate", pattern.getSuccessRate());
        return entry;
    }
    
    private Instant parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return Instant.now();
        try {
            return Instant.parse(timestamp);
        } catch (Exception e) {
            return Instant.now();
        }
    }
    
    // ==================== 文件操作辅助方法 ====================
    
    private Mono<MemoryStore> loadStore() {
        ensureInitialized();
        if (memoryStore != null) {
            return Mono.just(memoryStore);
        }
        Path file = memoryDir.resolve(STORE_FILENAME);
        if (!Files.exists(file)) {
            return Mono.just(createNewStore());
        }
        return Mono.fromCallable(() -> {
            MemoryStore store = objectMapper.readValue(file.toFile(), MemoryStore.class);
            this.memoryStore = store;
            return store;
        }).onErrorResume(e -> {
            log.error("加载记忆存储失败: {}", STORE_FILENAME, e);
            return Mono.just(createNewStore());
        });
    }
    
    private Mono<Void> saveStore(MemoryStore store) {
        ensureInitialized();
        Path file = memoryDir.resolve(STORE_FILENAME);
        return Mono.fromRunnable(() -> {
            try {
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(file.toFile(), store);
                this.memoryStore = store;
                log.trace("保存记忆存储: {}", STORE_FILENAME);
            } catch (IOException e) {
                log.error("保存记忆存储失败: {}", STORE_FILENAME, e);
            }
        });
    }
    
    private MemoryStore createNewStore() {
        MemoryStore store = new MemoryStore();
        store.setVersion("1.0");
        if (memoryDir != null) {
            store.setWorkspaceRoot(memoryDir.getParent().getParent().toString());
        }
        return store;
    }
    
    public Path getMemoryDir() {
        return memoryDir;
    }
}
