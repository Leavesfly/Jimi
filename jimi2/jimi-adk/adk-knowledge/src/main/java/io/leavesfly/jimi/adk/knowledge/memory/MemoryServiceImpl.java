package io.leavesfly.jimi.adk.knowledge.memory;

import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.knowledge.api.query.MemoryQuery;
import io.leavesfly.jimi.adk.knowledge.api.result.MemoryResult;
import io.leavesfly.jimi.adk.knowledge.api.spi.MemoryService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 长期记忆服务实现（适配 MemoryManager）
 * 
 * <p>复用现有的 MemoryManager 实现，提供 SPI 接口适配。
 */
@Slf4j
public class MemoryServiceImpl implements MemoryService {
    
    private final MemoryManager memoryManager;
    private final MemoryConfig config;
    
    public MemoryServiceImpl(MemoryManager memoryManager, MemoryConfig config) {
        this.memoryManager = memoryManager;
        this.config = config;
    }
    
    @Override
    public Mono<Boolean> initialize(Runtime runtime) {
        return Mono.fromRunnable(() -> {
            memoryManager.initialize(runtime);
            log.info("MemoryService 初始化完成, workDir={}", runtime.getConfig().getWorkDir());
        }).thenReturn(true);
    }
    
    @Override
    public Mono<MemoryResult> query(MemoryQuery query) {
        if (!isEnabled()) {
            return Mono.just(MemoryResult.error("Memory 功能未启用"));
        }
        
        return executeQuery(query)
                .onErrorResume(e -> {
                    log.error("查询记忆失败", e);
                    return Mono.just(MemoryResult.error(e.getMessage()));
                });
    }
    
    private Mono<MemoryResult> executeQuery(MemoryQuery query) {
        switch (query.getType()) {
            case PROJECT_INSIGHT:
                return memoryManager.queryInsights(query.getQuery(), query.getLimit())
                        .map(insights -> {
                            List<MemoryResult.MemoryEntry> entries = insights.stream()
                                    .map(this::convertInsightToEntry)
                                    .collect(Collectors.toList());
                            return MemoryResult.success(entries);
                        });
                
            case TASK_HISTORY:
                if (query.getTimeRange() != null) {
                    return memoryManager.getTasksByTimeRange(
                            query.getTimeRange().getFrom(),
                            query.getTimeRange().getTo())
                            .map(tasks -> {
                                List<MemoryResult.MemoryEntry> entries = tasks.stream()
                                        .map(this::convertTaskHistoryToEntry)
                                        .collect(Collectors.toList());
                                return MemoryResult.success(entries);
                            });
                } else if (query.getQuery() != null && !query.getQuery().isEmpty()) {
                    return memoryManager.searchTaskHistory(query.getQuery(), query.getLimit())
                            .map(tasks -> {
                                List<MemoryResult.MemoryEntry> entries = tasks.stream()
                                        .map(this::convertTaskHistoryToEntry)
                                        .collect(Collectors.toList());
                                return MemoryResult.success(entries);
                            });
                } else {
                    return memoryManager.getRecentTasks(query.getLimit())
                            .map(tasks -> {
                                List<MemoryResult.MemoryEntry> entries = tasks.stream()
                                        .map(this::convertTaskHistoryToEntry)
                                        .collect(Collectors.toList());
                                return MemoryResult.success(entries);
                            });
                }
                
            case ERROR_PATTERN:
                if (query.getQuery() != null && !query.getQuery().isEmpty()) {
                    return memoryManager.findErrorPattern(query.getQuery(), null)
                            .map(pattern -> {
                                List<MemoryResult.MemoryEntry> entries = new ArrayList<>();
                                entries.add(convertErrorPatternToEntry(pattern));
                                return MemoryResult.success(entries);
                            })
                            .defaultIfEmpty(MemoryResult.success(new ArrayList<>()));
                } else {
                    return memoryManager.getMostFrequentErrors(query.getLimit())
                            .map(patterns -> {
                                List<MemoryResult.MemoryEntry> entries = patterns.stream()
                                        .map(this::convertErrorPatternToEntry)
                                        .collect(Collectors.toList());
                                return MemoryResult.success(entries);
                            });
                }
                
            case SESSION_SUMMARY:
                if (query.getQuery() != null && !query.getQuery().isEmpty()) {
                    return memoryManager.searchSessions(query.getQuery(), query.getLimit())
                            .map(sessions -> {
                                List<MemoryResult.MemoryEntry> entries = sessions.stream()
                                        .map(this::convertSessionToEntry)
                                        .collect(Collectors.toList());
                                return MemoryResult.success(entries);
                            });
                } else {
                    return memoryManager.getRecentSessions(query.getLimit())
                            .map(sessions -> {
                                List<MemoryResult.MemoryEntry> entries = sessions.stream()
                                        .map(this::convertSessionToEntry)
                                        .collect(Collectors.toList());
                                return MemoryResult.success(entries);
                            });
                }
                
            case ALL:
            default:
                // 查询所有类型的记忆（使用语义检索）
                return memoryManager.queryInsights(query.getQuery(), query.getLimit())
                        .map(insights -> {
                            List<MemoryResult.MemoryEntry> entries = insights.stream()
                                    .map(this::convertInsightToEntry)
                                    .collect(Collectors.toList());
                            return MemoryResult.success(entries);
                        });
        }
    }
    
    @Override
    public Mono<MemoryResult> add(MemoryQuery query) {
        if (!isEnabled()) {
            return Mono.just(MemoryResult.error("Memory 功能未启用"));
        }
        
        switch (query.getType()) {
            case PROJECT_INSIGHT:
                ProjectInsight insight = ProjectInsight.builder()
                        .content(query.getContent())
                        .category("user_added")
                        .source("api")
                        .timestamp(Instant.now())
                        .confidence(1.0)
                        .build();
                return memoryManager.addInsight(insight)
                        .thenReturn(MemoryResult.operationSuccess(insight.getId()));
                
            case ERROR_PATTERN:
                ErrorPattern pattern = ErrorPattern.builder()
                        .errorMessage(query.getContent())
                        .firstSeen(Instant.now())
                        .lastSeen(Instant.now())
                        .build();
                return memoryManager.addOrUpdateErrorPattern(pattern)
                        .thenReturn(MemoryResult.operationSuccess(pattern.getId()));
                
            case TASK_HISTORY:
                TaskHistory task = TaskHistory.builder()
                        .userQuery(query.getContent())
                        .summary(query.getContent())
                        .timestamp(Instant.now())
                        .build();
                return memoryManager.addTaskHistory(task)
                        .thenReturn(MemoryResult.operationSuccess(task.getId()));
                
            default:
                return Mono.just(MemoryResult.error("不支持添加此类型的记忆: " + query.getType()));
        }
    }
    
    @Override
    public Mono<MemoryResult> extractFromSession(Runtime runtime) {
        // 暂时返回空操作，可以后续扩展
        return Mono.just(MemoryResult.builder()
                .success(true)
                .totalCount(0)
                .build());
    }
    
    @Override
    public Mono<MemoryResult> delete(String memoryId) {
        // MemoryManager 目前没有直接的删除 API
        // 可以后续扩展
        return Mono.just(MemoryResult.error("删除功能暂未实现"));
    }
    
    @Override
    public boolean isEnabled() {
        return config != null && config.isLongTermEnabled();
    }
    
    // ==================== 转换方法 ====================
    
    private MemoryResult.MemoryEntry convertInsightToEntry(ProjectInsight insight) {
        return MemoryResult.MemoryEntry.builder()
                .id(insight.getId())
                .type("PROJECT_INSIGHT")
                .content(insight.getContent())
                .createdAt(insight.getTimestamp())
                .accessCount(insight.getAccessCount())
                .relevanceScore(insight.getConfidence())
                .build();
    }
    
    private MemoryResult.MemoryEntry convertTaskHistoryToEntry(TaskHistory task) {
        return MemoryResult.MemoryEntry.builder()
                .id(task.getId())
                .type("TASK_HISTORY")
                .content(task.getSummary())
                .createdAt(task.getTimestamp())
                .build();
    }
    
    private MemoryResult.MemoryEntry convertErrorPatternToEntry(ErrorPattern pattern) {
        return MemoryResult.MemoryEntry.builder()
                .id(pattern.getId())
                .type("ERROR_PATTERN")
                .content(pattern.getSolution())
                .createdAt(pattern.getFirstSeen())
                .updatedAt(pattern.getLastSeen())
                .accessCount(pattern.getOccurrenceCount())
                .build();
    }
    
    private MemoryResult.MemoryEntry convertSessionToEntry(SessionSummary session) {
        return MemoryResult.MemoryEntry.builder()
                .id(session.getSessionId())
                .type("SESSION_SUMMARY")
                .content(session.getOutcome())
                .createdAt(session.getStartTime())
                .updatedAt(session.getEndTime())
                .build();
    }
}
