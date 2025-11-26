package io.leavesfly.jimi.retrieval;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存型向量存储实现
 * <p>
 * 特点：
 * - 纯内存存储（快速但不持久）
 * - 支持线性扫描相似度搜索
 * - 可选持久化到文件
 * <p>
 * 适合场景：
 * - 小型项目（< 10000个片段）
 * - 原型开发和测试
 * - 后续可替换为基于索引的实现（HNSW等）
 */
@Slf4j
public class InMemoryVectorStore implements VectorStore {

    private final Map<String, CodeChunk> chunks = new ConcurrentHashMap<>();
    private final Map<String, String> fileMD5Cache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private Path indexPath;

    public InMemoryVectorStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Boolean> add(CodeChunk chunk) {
        return Mono.fromCallable(() -> {
            chunks.put(chunk.getId(), chunk);
            log.debug("Added chunk: {}", chunk.getDescription());
            return true;
        });
    }

    @Override
    public Mono<Integer> addBatch(List<CodeChunk> chunkList) {
        return Mono.fromCallable(() -> {
            int count = 0;
            for (CodeChunk chunk : chunkList) {
                chunks.put(chunk.getId(), chunk);
                count++;
            }
            log.info("Added {} chunks to index", count);
            return count;
        });
    }

    @Override
    public Mono<Boolean> delete(String id) {
        return Mono.fromCallable(() -> {
            CodeChunk removed = chunks.remove(id);
            return removed != null;
        });
    }

    @Override
    public Mono<Integer> deleteByFilePath(String filePath) {
        return Mono.fromCallable(() -> {
            List<String> toRemove = chunks.values().stream()
                    .filter(chunk -> chunk.getFilePath().equals(filePath))
                    .map(CodeChunk::getId)
                    .collect(Collectors.toList());
            
            toRemove.forEach(chunks::remove);
            log.info("Deleted {} chunks from file: {}", toRemove.size(), filePath);
            return toRemove.size();
        });
    }

    @Override
    public Mono<List<SearchResult>> search(float[] queryVector, int topK) {
        return search(queryVector, topK, null);
    }

    @Override
    public Mono<List<SearchResult>> search(float[] queryVector, int topK, SearchFilter filter) {
        return Mono.fromCallable(() -> {
            List<SearchResult> results = new ArrayList<>();

            for (CodeChunk chunk : chunks.values()) {
                // 应用过滤条件
                if (filter != null && !matchesFilter(chunk, filter)) {
                    continue;
                }

                // 检查是否有向量
                if (chunk.getEmbedding() == null || chunk.getEmbedding().length == 0) {
                    continue;
                }

                // 计算余弦相似度
                double score = cosineSimilarity(queryVector, chunk.getEmbedding());

                results.add(SearchResult.builder()
                        .chunk(chunk)
                        .score(score)
                        .build());
            }

            // 按分数降序排序，取TopK
            results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            
            if (results.size() > topK) {
                results = results.subList(0, topK);
            }

            log.debug("Search completed: {} results (topK={})", results.size(), topK);
            return results;
        });
    }

    @Override
    public Mono<IndexStats> getStats() {
        return Mono.fromCallable(() -> {
            Set<String> uniqueFiles = chunks.values().stream()
                    .map(CodeChunk::getFilePath)
                    .collect(Collectors.toSet());

            long maxUpdated = chunks.values().stream()
                    .mapToLong(CodeChunk::getUpdatedAt)
                    .max()
                    .orElse(0L);

            // 估算索引大小（粗略计算）
            long estimatedSize = chunks.size() * 1024L; // 假设每个chunk约1KB

            return IndexStats.builder()
                    .totalChunks(chunks.size())
                    .totalFiles(uniqueFiles.size())
                    .lastUpdated(maxUpdated)
                    .indexSizeBytes(estimatedSize)
                    .storageType("in-memory")
                    .build();
        });
    }

    @Override
    public Mono<Boolean> clear() {
        return Mono.fromCallable(() -> {
            int before = chunks.size();
            chunks.clear();
            log.info("Cleared {} chunks from index", before);
            return true;
        });
    }

    @Override
    public Mono<Boolean> save() {
        if (indexPath == null) {
            return Mono.just(false);
        }

        return Mono.fromCallable(() -> {
            try {
                // 创建目录
                Files.createDirectories(indexPath.getParent());

                // 保存为JSONL格式（每行一个chunk的JSON）
                Path chunksFile = indexPath.resolve("chunks.jsonl");
                
                try (BufferedWriter writer = Files.newBufferedWriter(chunksFile, 
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    
                    for (CodeChunk chunk : chunks.values()) {
                        // 使用包装类以便序列化时忽略embedding
                        ChunkWrapper wrapper = new ChunkWrapper(chunk);
                        String json = objectMapper.writeValueAsString(wrapper);
                        writer.write(json);
                        writer.newLine();
                    }
                }

                // 保存向量为二进制文件
                Path vectorsFile = indexPath.resolve("vectors.bin");
                try (DataOutputStream dos = new DataOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(vectorsFile,
                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))) {
                    
                    for (CodeChunk chunk : chunks.values()) {
                        // 写入chunk ID长度和ID
                        byte[] idBytes = chunk.getId().getBytes();
                        dos.writeInt(idBytes.length);
                        dos.write(idBytes);
                        
                        // 写入向量
                        float[] embedding = chunk.getEmbedding();
                        if (embedding != null) {
                            dos.writeInt(embedding.length);
                            for (float v : embedding) {
                                dos.writeFloat(v);
                            }
                        } else {
                            dos.writeInt(0);
                        }
                    }
                }

                log.info("Saved {} chunks to {}", chunks.size(), indexPath);
                
                // 保存MD5缓存
                Path md5File = indexPath.resolve("md5_cache.json");
                objectMapper.writeValue(md5File.toFile(), fileMD5Cache);
                log.debug("Saved MD5 cache: {} files", fileMD5Cache.size());
                
                return true;
            } catch (IOException e) {
                log.error("Failed to save index", e);
                return false;
            }
        });
    }

    @Override
    public Mono<Boolean> load(Path indexPath) {
        this.indexPath = indexPath;
        
        return Mono.fromCallable(() -> {
            if (!Files.exists(indexPath)) {
                log.warn("Index path does not exist: {}", indexPath);
                return false;
            }

            Path chunksFile = indexPath.resolve("chunks.jsonl");
            Path vectorsFile = indexPath.resolve("vectors.bin");

            if (!Files.exists(chunksFile) || !Files.exists(vectorsFile)) {
                log.warn("Index files not found in: {}", indexPath);
                return false;
            }

            try {
                // 加载chunk元数据
                Map<String, CodeChunk> loadedChunks = new HashMap<>();
                
                try (BufferedReader reader = Files.newBufferedReader(chunksFile)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        ChunkWrapper wrapper = objectMapper.readValue(line, ChunkWrapper.class);
                        CodeChunk chunk = wrapper.toCodeChunk();
                        loadedChunks.put(chunk.getId(), chunk);
                    }
                }

                // 加载向量
                try (DataInputStream dis = new DataInputStream(
                        new BufferedInputStream(Files.newInputStream(vectorsFile)))) {
                    
                    while (dis.available() > 0) {
                        // 读取chunk ID
                        int idLength = dis.readInt();
                        byte[] idBytes = new byte[idLength];
                        dis.readFully(idBytes);
                        String chunkId = new String(idBytes);
                        
                        // 读取向量
                        int vectorLength = dis.readInt();
                        float[] embedding = null;
                        if (vectorLength > 0) {
                            embedding = new float[vectorLength];
                            for (int i = 0; i < vectorLength; i++) {
                                embedding[i] = dis.readFloat();
                            }
                        }
                        
                        // 设置向量到chunk
                        CodeChunk chunk = loadedChunks.get(chunkId);
                        if (chunk != null) {
                            chunk.setEmbedding(embedding);
                        }
                    }
                }

                // 更新到内存
                chunks.clear();
                chunks.putAll(loadedChunks);

                // 加载MD5缓存
                Path md5File = indexPath.resolve("md5_cache.json");
                if (Files.exists(md5File)) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> loadedMD5 = objectMapper.readValue(
                            md5File.toFile(), Map.class);
                    fileMD5Cache.clear();
                    fileMD5Cache.putAll(loadedMD5);
                    log.debug("Loaded MD5 cache: {} files", fileMD5Cache.size());
                }

                log.info("Loaded {} chunks from {}", chunks.size(), indexPath);
                return true;
            } catch (IOException e) {
                log.error("Failed to load index", e);
                return false;
            }
        });
    }

    /**
     * 计算余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 检查chunk是否匹配过滤条件
     */
    private boolean matchesFilter(CodeChunk chunk, SearchFilter filter) {
        if (filter.getLanguage() != null && !filter.getLanguage().equals(chunk.getLanguage())) {
            return false;
        }

        if (filter.getFilePattern() != null && !chunk.getFilePath().matches(filter.getFilePattern())) {
            return false;
        }

        if (filter.getSymbolPattern() != null && chunk.getSymbol() != null 
                && !chunk.getSymbol().matches(filter.getSymbolPattern())) {
            return false;
        }

        if (filter.getMinUpdatedAt() != null && chunk.getUpdatedAt() < filter.getMinUpdatedAt()) {
            return false;
        }

        return true;
    }

    /**
     * 更新文件MD5缓存
     */
    public void updateFileMD5(String filePath, String md5) {
        fileMD5Cache.put(filePath, md5);
    }

    /**
     * 获取文件MD5
     */
    public String getFileMD5(String filePath) {
        return fileMD5Cache.get(filePath);
    }

    /**
     * 检查文件是否需要更新（MD5变化）
     */
    public boolean fileNeedsUpdate(String filePath, String currentMD5) {
        String cachedMD5 = fileMD5Cache.get(filePath);
        if (cachedMD5 == null) {
            return true;
        }
        return !cachedMD5.equals(currentMD5);
    }

    /**
     * 包装类用于JSON序列化（避免序列化embedding）
     */
    private static class ChunkWrapper {
        public String id;
        public String content;
        public String filePath;
        public String symbol;
        public int startLine;
        public int endLine;
        public String language;
        public String contentHash;
        public long updatedAt;
        public Map<String, String> metadata;

        @JsonIgnore
        private float[] embedding; // 不序列化到JSON

        public ChunkWrapper() {}

        public ChunkWrapper(CodeChunk chunk) {
            this.id = chunk.getId();
            this.content = chunk.getContent();
            this.filePath = chunk.getFilePath();
            this.symbol = chunk.getSymbol();
            this.startLine = chunk.getStartLine();
            this.endLine = chunk.getEndLine();
            this.language = chunk.getLanguage();
            this.contentHash = chunk.getContentHash();
            this.updatedAt = chunk.getUpdatedAt();
            this.metadata = chunk.getMetadata();
        }

        public CodeChunk toCodeChunk() {
            return CodeChunk.builder()
                    .id(id)
                    .content(content)
                    .filePath(filePath)
                    .symbol(symbol)
                    .startLine(startLine)
                    .endLine(endLine)
                    .language(language)
                    .contentHash(contentHash)
                    .updatedAt(updatedAt)
                    .metadata(metadata)
                    .build();
        }
    }
}
