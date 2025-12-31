package io.leavesfly.jimi.knowledge.rag;

import io.leavesfly.jimi.config.info.VectorIndexConfig;
import io.leavesfly.jimi.core.engine.runtime.Runtime;
import io.leavesfly.jimi.knowledge.domain.query.RetrievalQuery;
import io.leavesfly.jimi.knowledge.domain.result.RetrievalResult;
import io.leavesfly.jimi.knowledge.spi.RagService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 向量检索服务实现（适配 VectorStore 和 EmbeddingProvider）
 * 
 * <p>复用现有的检索组件，提供 SPI 接口适配。
 */
@Slf4j
public class RagServiceImpl implements RagService {
    
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    private final Chunker chunker;
    private final VectorIndexConfig config;
    
    private volatile Path workDir;
    
    public RagServiceImpl(VectorStore vectorStore,
                          EmbeddingProvider embeddingProvider,
                          Chunker chunker,
                          VectorIndexConfig config) {
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
        this.chunker = chunker;
        this.config = config;
    }
    
    @Override
    public Mono<Boolean> initialize(Runtime runtime) {
        return Mono.fromRunnable(() -> {
            this.workDir = runtime.getWorkDir();
            log.info("RagService 初始化完成, workDir={}", workDir);
        }).thenReturn(true);
    }
    
    @Override
    public Mono<RetrievalResult> retrieve(RetrievalQuery query) {
        if (!isEnabled()) {
            return Mono.just(RetrievalResult.error("Retrieval 功能未启用"));
        }
        
        long startTime = System.currentTimeMillis();
        
        return embeddingProvider.embed(query.getQuery())
                .flatMap(queryVector -> {
                    // 构建过滤条件
                    VectorStore.SearchFilter filter = null;
                    if (query.getFilter() != null) {
                        filter = VectorStore.SearchFilter.builder()
                                .language(query.getFilter().getLanguage())
                                .filePattern(query.getFilter().getFilePattern())
                                .symbolPattern(query.getFilter().getSymbolPattern())
                                .minUpdatedAt(query.getFilter().getMinUpdatedAt())
                                .build();
                    }
                    
                    if (filter != null) {
                        return vectorStore.search(queryVector, query.getTopK(), filter);
                    } else {
                        return vectorStore.search(queryVector, query.getTopK());
                    }
                })
                .map(results -> {
                    long elapsedMs = System.currentTimeMillis() - startTime;
                    
                    // 根据 minScore 过滤结果
                    List<VectorStore.SearchResult> filteredResults = results.stream()
                            .filter(r -> r.getScore() >= query.getMinScore())
                            .collect(Collectors.toList());
                    
                    // 转换为领域结果
                    List<RetrievalResult.CodeChunkResult> chunkResults = filteredResults.stream()
                            .map(r -> convertToChunkResult(r, query.isIncludeContent()))
                            .collect(Collectors.toList());
                    
                    return RetrievalResult.success(query.getQuery(), chunkResults, elapsedMs);
                })
                .onErrorResume(e -> {
                    log.error("检索失败", e);
                    return Mono.just(RetrievalResult.error(e.getMessage()));
                });
    }
    
    @Override
    public Mono<RetrievalResult> buildIndex(Path projectRoot) {
        if (!isEnabled()) {
            return Mono.just(RetrievalResult.error("Retrieval 功能未启用"));
        }
        
        log.info("开始构建检索索引: {}", projectRoot);
        long startTime = System.currentTimeMillis();
        
        // 获取配置的文件扩展名和排除模式
        Set<String> extensions = Arrays.stream(config.getFileExtensions().split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
        
        // 遍历项目文件并分块
        return findSourceFiles(projectRoot, extensions)
                .flatMap(filePath -> {
                    try {
                        String content = Files.readString(filePath);
                        String relativePath = projectRoot.relativize(filePath).toString();

                        return chunker.chunk(relativePath, content, config.getChunkSize(), config.getChunkOverlap());

                    } catch (IOException e) {
                        log.warn("读取文件失败: {}", filePath, e);
                        return Flux.empty();
                    }
                })
                .collectList()
                .flatMap(chunks -> {
                    log.info("代码分块完成, chunk数量: {}", chunks.size());
                    
                    return vectorStore.addBatch(chunks)
                            .map(addedCount -> {
                                long elapsedMs = System.currentTimeMillis() - startTime;
                                log.info("索引构建完成, 耗时: {}ms, 添加: {} chunks", 
                                        elapsedMs, addedCount);
                                
                                return RetrievalResult.builder()
                                        .success(true)
                                        .elapsedMs(elapsedMs)
                                        .indexStats(RetrievalResult.IndexStats.builder()
                                                .totalChunks(addedCount)
                                                .build())
                                        .build();
                            });
                })
                .onErrorResume(e -> {
                    log.error("索引构建失败", e);
                    return Mono.just(RetrievalResult.error(e.getMessage()));
                });
    }
    
    /**
     * 查找源代码文件
     */
    private Flux<Path> findSourceFiles(Path projectRoot, Set<String> extensions) {
        return Flux.defer(() -> {
            try {
                Stream<Path> files = Files.walk(projectRoot)
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String fileName = path.getFileName().toString();
                            return extensions.stream().anyMatch(fileName::endsWith);
                        })
                        .filter(path -> !isExcluded(projectRoot, path));
                return Flux.fromStream(files);
            } catch (IOException e) {
                return Flux.error(e);
            }
        });
    }
    
    /**
     * 检查文件是否被排除
     */
    private boolean isExcluded(Path projectRoot, Path filePath) {
        String relativePath = projectRoot.relativize(filePath).toString();
        String[] excludePatterns = config.getExcludePatterns().split(",");
        for (String pattern : excludePatterns) {
            pattern = pattern.trim();
            // 简化的 glob 匹配
            if (pattern.startsWith("**")) {
                String suffix = pattern.substring(2);
                if (relativePath.contains(suffix.replace("**", ""))) {
                    return true;
                }
            }
        }
        return false;
    }
    
    @Override
    public Mono<Boolean> save() {
        return vectorStore.save();
    }
    
    @Override
    public Mono<Boolean> load(Path indexPath) {
        return vectorStore.load(indexPath);
    }
    
    @Override
    public Mono<RetrievalResult.IndexStats> getStats() {
        return vectorStore.getStats()
                .map(stats -> RetrievalResult.IndexStats.builder()
                        .totalChunks(stats.getTotalChunks())
                        .totalFiles(stats.getTotalFiles())
                        .lastUpdated(stats.getLastUpdated())
                        .indexSizeBytes(stats.getIndexSizeBytes())
                        .build());
    }
    
    @Override
    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }
    
    @Override
    public Mono<Boolean> clear() {
        return vectorStore.clear().thenReturn(true);
    }
    
    // ==================== 转换方法 ====================
    
    private RetrievalResult.CodeChunkResult convertToChunkResult(VectorStore.SearchResult result,
                                                                  boolean includeContent) {
        CodeChunk chunk = result.getChunk();
        
        return RetrievalResult.CodeChunkResult.builder()
                .id(chunk.getId())
                .content(includeContent ? chunk.getContent() : null)
                .filePath(chunk.getFilePath())
                .symbol(chunk.getSymbol())
                .startLine(chunk.getStartLine())
                .endLine(chunk.getEndLine())
                .language(chunk.getLanguage())
                .score(result.getScore())
                .build();
    }
}
