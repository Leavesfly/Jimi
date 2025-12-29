package io.leavesfly.jimi.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.config.MemoryConfig;
import io.leavesfly.jimi.engine.runtime.Runtime;
import io.leavesfly.jimi.retrieval.CodeChunk;
import io.leavesfly.jimi.retrieval.EmbeddingProvider;
import io.leavesfly.jimi.retrieval.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 记忆管理器
 * 负责长期记忆的加载、保存和查询
 * 支持语义向量检索（复用现有 VectorStore 和 EmbeddingProvider）
 */
@Slf4j
@Component
public class MemoryManager {
    
    private static final String MEMORY_TYPE = "long_term_memory";
    
    private final ObjectMapper objectMapper;
    private final MemoryConfig config;
    private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();
    
    // 复用现有的向量检索组件
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    
    private Path memoryDir;
    private boolean initialized = false;
    
    public MemoryManager(
            ObjectMapper objectMapper, 
            MemoryConfig config,
            @Autowired(required = false) VectorStore vectorStore,
            @Autowired(required = false) EmbeddingProvider embeddingProvider) {
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
     * 
     * @param runtime 运行时上下文
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
            initialized = true;
        } catch (IOException e) {
            log.error("创建记忆目录失败: {}", memoryDir, e);
        }
    }
    
    /**
     * 检查是否已初始化
     */
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("MemoryManager未初始化，请先调用initialize()方法");
        }
    }
    
    // ==================== 用户偏好管理 ====================
    
    /**
     * 加载用户偏好
     */
    public Mono<UserPreferences> loadPreferences() {
        return loadFromFile("preferences.json", UserPreferences.class)
                .defaultIfEmpty(UserPreferences.getDefault())
                .doOnNext(prefs -> log.debug("加载用户偏好: language={}, style={}", 
                        prefs.getCommunication().getLanguage(),
                        prefs.getCoding().getStyle()));
    }
    
    /**
     * 保存用户偏好
     */
    public Mono<Void> savePreferences(UserPreferences prefs) {
        return saveToFile("preferences.json", prefs)
                .doOnSuccess(v -> log.debug("保存用户偏好完成"));
    }
    
    // ==================== 项目知识管理 ====================
    
    /**
     * 查询项目知识（语义检索优先，回退到关键词）
     * 
     * @param query 查询文本
     * @param limit 返回数量限制
     * @return 匹配的知识列表
     */
    public Mono<List<ProjectInsight>> queryInsights(String query, int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        
        // 优先使用语义检索
        if (isSemanticSearchEnabled()) {
            return queryBySemantics(query, limit);
        }
        
        // 回退到关键词检索
        return queryByKeyword(query, limit);
    }
    
    /**
     * 检查是否启用语义检索
     */
    public boolean isSemanticSearchEnabled() {
        return vectorStore != null && embeddingProvider != null;
    }
    
    /**
     * 语义向量检索（复用现有 VectorStore）
     * 
     * @param query 查询文本
     * @param topK 返回数量
     * @return 匹配的知识列表
     */
    public Mono<List<ProjectInsight>> queryBySemantics(String query, int topK) {
        if (!isSemanticSearchEnabled()) {
            log.warn("语义检索未启用，回退到关键词检索");
            return queryByKeyword(query, topK);
        }
        
        return embeddingProvider.embed(query)
                .flatMap(queryVector -> vectorStore.search(queryVector, topK * 2)) // 多查些用于过滤
                .map(results -> results.stream()
                        .filter(r -> isMemoryChunk(r.getChunk())) // 过滤出记忆类型
                        .limit(topK)
                        .map(this::convertToInsight)
                        .collect(Collectors.toList()))
                .doOnNext(insights -> {
                    if (!insights.isEmpty()) {
                        log.debug("语义检索 [{}] 匹配 {} 条", query, insights.size());
                    }
                })
                .onErrorResume(e -> {
                    log.warn("语义检索失败，回退到关键词: {}", e.getMessage());
                    return queryByKeyword(query, topK);
                });
    }
    
    /**
     * 检查是否为记忆类型的 Chunk
     */
    private boolean isMemoryChunk(CodeChunk chunk) {
        if (chunk == null || chunk.getMetadata() == null) {
            return false;
        }
        return MEMORY_TYPE.equals(chunk.getMetadata().get("type"));
    }
    
    /**
     * 关键词检索（原有逻辑）
     */
    private Mono<List<ProjectInsight>> queryByKeyword(String keyword, int limit) {
        return loadFromFile("project_insights.json", ProjectInsightsStore.class)
                .map(store -> store.search(keyword, limit))
                .flatMap(insights -> {
                    if (!insights.isEmpty()) {
                        return saveInsightsStore().thenReturn(insights);
                    }
                    return Mono.just(insights);
                })
                .defaultIfEmpty(List.of())
                .doOnNext(insights -> {
                    if (!insights.isEmpty()) {
                        log.debug("关键词检索 [{}] 匹配 {} 条", keyword, insights.size());
                    }
                });
    }
    
    /**
     * 将 VectorStore 结果转换为 ProjectInsight
     */
    private ProjectInsight convertToInsight(VectorStore.SearchResult result) {
        CodeChunk chunk = result.getChunk();
        Map<String, String> metadata = chunk.getMetadata();
        
        return ProjectInsight.builder()
                .id(chunk.getId())
                .category(metadata.getOrDefault("category", "general"))
                .content(chunk.getContent())
                .source(metadata.getOrDefault("source", "unknown"))
                .timestamp(parseTimestamp(metadata.get("timestamp")))
                .confidence(result.getScore())
                .accessCount(0)
                .lastAccessed(Instant.now())
                .build();
    }
    
    /**
     * 解析时间戳
     */
    private Instant parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return Instant.now();
        }
        try {
            return Instant.parse(timestamp);
        } catch (Exception e) {
            return Instant.now();
        }
    }
    
    /**
     * 添加项目知识（同时存储到 JSON 和 VectorStore）
     * 
     * @param insight 知识条目
     */
    public Mono<Void> addInsight(ProjectInsight insight) {
        if (!config.isLongTermEnabled() || !config.isAutoExtract()) {
            return Mono.empty();
        }
        
        // 1. 存储到 JSON 文件（原有逻辑）
        Mono<Void> jsonStore = loadFromFile("project_insights.json", ProjectInsightsStore.class)
                .defaultIfEmpty(createNewInsightsStore())
                .flatMap(store -> {
                    store.add(insight);
                    store.prune(config.getMaxInsights());
                    return saveToFile("project_insights.json", store);
                });
        
        // 2. 如果启用语义检索，同时存储到 VectorStore
        Mono<Void> vectorStoreOp = Mono.empty();
        if (isSemanticSearchEnabled()) {
            vectorStoreOp = embeddingProvider.embed(insight.getContent())
                    .flatMap(vector -> {
                        CodeChunk chunk = CodeChunk.builder()
                                .id(insight.getId())
                                .content(insight.getContent())
                                .embedding(vector)
                                .metadata(Map.of(
                                        "type", MEMORY_TYPE,
                                        "category", insight.getCategory(),
                                        "source", insight.getSource() != null ? insight.getSource() : "",
                                        "timestamp", insight.getTimestamp().toString()
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
        
        return jsonStore.then(vectorStoreOp)
                .doOnSuccess(v -> log.debug("添加知识: [{}] {}{}", 
                        insight.getCategory(), 
                        insight.getContent().substring(0, Math.min(50, insight.getContent().length())),
                        isSemanticSearchEnabled() ? " (已向量化)" : ""));
    }
    
    /**
     * 创建新的知识存储
     */
    private ProjectInsightsStore createNewInsightsStore() {
        ProjectInsightsStore store = new ProjectInsightsStore();
        store.setVersion("1.0");
        if (memoryDir != null) {
            store.setWorkspaceRoot(memoryDir.getParent().getParent().toString());
        }
        return store;
    }
    
    /**
     * 保存知识存储（用于更新访问记录）
     */
    private Mono<Void> saveInsightsStore() {
        Object cached = cache.get("project_insights.json");
        if (cached instanceof ProjectInsightsStore) {
            return saveToFile("project_insights.json", cached);
        }
        return Mono.empty();
    }
    
    // ==================== 任务模式管理 ====================
    
    /**
     * 查询任务模式
     * 
     * @param trigger 触发词
     * @return 匹配的任务模式
     */
    public Mono<TaskPattern> findPattern(String trigger) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        
        return loadFromFile("task_patterns.json", TaskPatternStore.class)
                .map(store -> store.findByTrigger(trigger))
                .flatMap(pattern -> {
                    if (pattern != null) {
                        // 更新使用记录后保存
                        return savePatternStore()
                                .thenReturn(pattern);
                    }
                    return Mono.empty();
                })
                .doOnNext(pattern -> log.debug("找到任务模式: {}", pattern.getTrigger()));
    }
    
    /**
     * 添加任务模式
     * 
     * @param pattern 任务模式
     */
    public Mono<Void> addPattern(TaskPattern pattern) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        
        return loadFromFile("task_patterns.json", TaskPatternStore.class)
                .defaultIfEmpty(new TaskPatternStore())
                .flatMap(store -> {
                    store.add(pattern);
                    return saveToFile("task_patterns.json", store);
                })
                .doOnSuccess(v -> log.debug("添加任务模式: {}", pattern.getTrigger()));
    }
    
    /**
     * 保存模式存储（用于更新使用记录）
     */
    private Mono<Void> savePatternStore() {
        Object cached = cache.get("task_patterns.json");
        if (cached instanceof TaskPatternStore) {
            return saveToFile("task_patterns.json", cached);
        }
        return Mono.empty();
    }
    
    // ==================== 任务历史管理 ====================
    
    /**
     * 添加任务历史
     * 
     * @param task 任务历史
     */
    public Mono<Void> addTaskHistory(TaskHistory task) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        
        return loadFromFile("task_history.json", TaskHistoryStore.class)
                .defaultIfEmpty(new TaskHistoryStore())
                .flatMap(store -> {
                    store.add(task);
                    store.prune(config.getMaxTaskHistory(), config.getInsightExpiryDays());
                    return saveToFile("task_history.json", store);
                })
                .doOnSuccess(v -> log.info("记录任务历史: {}", task.getUserQuery()));
    }
    
    /**
     * 获取最近的任务历史
     * 
     * @param limit 返回数量
     * @return 任务列表
     */
    public Mono<List<TaskHistory>> getRecentTasks(int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        
        return loadFromFile("task_history.json", TaskHistoryStore.class)
                .map(store -> store.getRecent(limit))
                .defaultIfEmpty(List.of());
    }
    
    /**
     * 按关键词搜索任务历史
     * 
     * @param keyword 关键词
     * @param limit 返回数量
     * @return 任务列表
     */
    public Mono<List<TaskHistory>> searchTaskHistory(String keyword, int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        
        return loadFromFile("task_history.json", TaskHistoryStore.class)
                .map(store -> store.searchByKeyword(keyword, limit))
                .defaultIfEmpty(List.of());
    }
    
    /**
     * 按时间范围查询任务历史
     * 
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 任务列表
     */
    public Mono<List<TaskHistory>> getTasksByTimeRange(Instant startTime, Instant endTime) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        
        return loadFromFile("task_history.json", TaskHistoryStore.class)
                .map(store -> store.getByTimeRange(startTime, endTime))
                .defaultIfEmpty(List.of());
    }
    
    // ==================== 会话摘要管理 ====================
    
    /**
     * 添加会话摘要
     * 
     * @param session 会话摘要
     */
    public Mono<Void> addSessionSummary(SessionSummary session) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        
        return loadFromFile("session_summaries.json", SessionSummaryStore.class)
                .defaultIfEmpty(new SessionSummaryStore())
                .flatMap(store -> {
                    store.add(session);
                    store.prune(config.getMaxTaskHistory(), config.getInsightExpiryDays());
                    return saveToFile("session_summaries.json", store);
                })
                .doOnSuccess(v -> log.info("记录会话摘要: {}", session.getGoal()));
    }
    
    /**
     * 获取最近一次会话摘要
     */
    public Mono<SessionSummary> getLastSession() {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        
        return loadFromFile("session_summaries.json", SessionSummaryStore.class)
                .map(SessionSummaryStore::getLastSession);
    }
    
    /**
     * 获取最近的会话摘要
     * 
     * @param limit 返回数量
     * @return 会话列表
     */
    public Mono<List<SessionSummary>> getRecentSessions(int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        
        return loadFromFile("session_summaries.json", SessionSummaryStore.class)
                .map(store -> store.getRecent(limit))
                .defaultIfEmpty(List.of());
    }
    
    /**
     * 按关键词搜索会话摘要
     * 
     * @param keyword 关键词
     * @param limit 返回数量
     * @return 会话列表
     */
    public Mono<List<SessionSummary>> searchSessions(String keyword, int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        
        return loadFromFile("session_summaries.json", SessionSummaryStore.class)
                .map(store -> store.searchByKeyword(keyword, limit))
                .defaultIfEmpty(List.of());
    }
    
    // ==================== 错误模式管理 ====================
    
    /**
     * 添加或更新错误模式
     * 
     * @param pattern 错误模式
     */
    public Mono<Void> addOrUpdateErrorPattern(ErrorPattern pattern) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        
        return loadFromFile("error_patterns.json", ErrorPatternStore.class)
                .defaultIfEmpty(new ErrorPatternStore())
                .flatMap(store -> {
                    store.addOrUpdate(pattern);
                    store.prune(config.getMaxInsights(), config.getInsightExpiryDays());
                    return saveToFile("error_patterns.json", store);
                })
                .doOnSuccess(v -> log.debug("记录错误模式: {}", pattern.getErrorType()));
    }
    
    /**
     * 记录错误解决成功
     */
    public Mono<Void> recordErrorResolution(String errorMessage, String context) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        
        return loadFromFile("error_patterns.json", ErrorPatternStore.class)
                .flatMap(store -> {
                    store.recordResolution(errorMessage, context);
                    return saveToFile("error_patterns.json", store);
                });
    }
    
    /**
     * 查找匹配的错误模式
     * 
     * @param errorMessage 错误消息
     * @param context 上下文
     * @return 匹配的错误模式
     */
    public Mono<ErrorPattern> findErrorPattern(String errorMessage, String context) {
        if (!config.isLongTermEnabled()) {
            return Mono.empty();
        }
        
        return loadFromFile("error_patterns.json", ErrorPatternStore.class)
                .flatMap(store -> Mono.justOrEmpty(store.findMatch(errorMessage, context)))
                .doOnNext(pattern -> log.debug("匹配到错误模式: {}", pattern.getErrorType()));
    }
    
    /**
     * 获取最常见的错误模式
     * 
     * @param limit 返回数量
     * @return 错误模式列表
     */
    public Mono<List<ErrorPattern>> getMostFrequentErrors(int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        
        return loadFromFile("error_patterns.json", ErrorPatternStore.class)
                .map(store -> store.getMostFrequent(limit))
                .defaultIfEmpty(List.of());
    }
    
    /**
     * 按工具名称获取错误模式
     * 
     * @param toolName 工具名称
     * @param limit 返回数量
     * @return 错误模式列表
     */
    public Mono<List<ErrorPattern>> getErrorsByTool(String toolName, int limit) {
        if (!config.isLongTermEnabled()) {
            return Mono.just(List.of());
        }
        
        return loadFromFile("error_patterns.json", ErrorPatternStore.class)
                .map(store -> store.getByTool(toolName, limit))
                .defaultIfEmpty(List.of());
    }
    
    // ==================== 文件操作辅助方法 ====================
    
    /**
     * 从文件加载数据
     */
    private <T> Mono<T> loadFromFile(String filename, Class<T> clazz) {
        ensureInitialized();
        
        Path file = memoryDir.resolve(filename);
        if (!Files.exists(file)) {
            return Mono.empty();
        }
        
        return Mono.fromCallable(() -> {
            T data = objectMapper.readValue(file.toFile(), clazz);
            cache.put(filename, data);
            return data;
        }).onErrorResume(e -> {
            log.error("加载记忆文件失败: {}", filename, e);
            return Mono.empty();
        });
    }
    
    /**
     * 保存数据到文件
     */
    private Mono<Void> saveToFile(String filename, Object data) {
        ensureInitialized();
        
        Path file = memoryDir.resolve(filename);
        
        return Mono.fromRunnable(() -> {
            try {
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(file.toFile(), data);
                cache.put(filename, data);
                log.trace("保存记忆文件: {}", filename);
            } catch (IOException e) {
                log.error("保存记忆文件失败: {}", filename, e);
            }
        });
    }
    
    /**
     * 获取记忆目录路径
     */
    public Path getMemoryDir() {
        return memoryDir;
    }
}
