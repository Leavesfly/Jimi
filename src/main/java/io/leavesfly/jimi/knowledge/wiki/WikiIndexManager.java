package io.leavesfly.jimi.knowledge.wiki;

import io.leavesfly.jimi.knowledge.rag.CodeChunk;
import io.leavesfly.jimi.knowledge.rag.EmbeddingProvider;
import io.leavesfly.jimi.knowledge.rag.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Wiki 索引管理器
 * <p>
 * 职责：
 * - 为文档生成提供代码检索增强
 * - 向量化 Wiki 文档内容（当 EmbeddingProvider 可用时）
 * - 当 EmbeddingProvider 不可用时，降级为文本关键词匹配
 * - 提供 Wiki 语义搜索 / 文本搜索能力
 */
@Slf4j
@Component
public class WikiIndexManager {
    
    @Autowired(required = false)
    private VectorStore vectorStore;
    
    @Autowired(required = false)
    private EmbeddingProvider embeddingProvider;
    
    @Value("${jimi.work-dir:#{null}}")
    private String configuredWorkDir;
    
    private static final int DEFAULT_TOP_K = 5;
    private static final String WIKI_METADATA_KEY = "wiki_doc";
    
    /**
     * 检索与文档章节相关的代码片段
     * <p>
     * 优先使用向量检索；当 EmbeddingProvider 不可用时，降级为文本关键词匹配。
     *
     * @param docSection 文档章节描述（如："系统架构", "API文档"）
     * @param topK       返回结果数量
     * @return 相关代码片段列表
     */
    public List<CodeChunk> retrieveRelevantCode(String docSection, int topK) {
        if (isVectorSearchAvailable()) {
            return retrieveRelevantCodeByVector(docSection, topK);
        }
        
        log.debug("EmbeddingProvider not available, falling back to text pattern matching");
        return retrieveRelevantCodeByTextMatch(docSection, topK);
    }
    
    /**
     * 通过向量检索相关代码片段
     */
    private List<CodeChunk> retrieveRelevantCodeByVector(String docSection, int topK) {
        try {
            float[] queryVector = embeddingProvider.embed(docSection).block();
            
            if (queryVector == null) {
                log.warn("Failed to generate embedding for: {}", docSection);
                return retrieveRelevantCodeByTextMatch(docSection, topK);
            }
            
            List<VectorStore.SearchResult> results = vectorStore.search(queryVector, topK).block();
            
            if (results == null || results.isEmpty()) {
                log.debug("No relevant code found via vector search for: {}", docSection);
                return new ArrayList<>();
            }
            
            log.info("Retrieved {} relevant code chunks via vector search for: {}", results.size(), docSection);
            
            return results.stream()
                    .map(VectorStore.SearchResult::getChunk)
                    .collect(Collectors.toList());
            
        } catch (Exception e) {
            log.error("Vector search failed for: {}, falling back to text match", docSection, e);
            return retrieveRelevantCodeByTextMatch(docSection, topK);
        }
    }
    
    /**
     * 通过文本关键词匹配检索相关代码片段
     * <p>
     * 从查询文本中提取关键词，扫描项目源代码文件，
     * 按关键词命中率排序返回最相关的代码片段。
     */
    private List<CodeChunk> retrieveRelevantCodeByTextMatch(String docSection, int topK) {
        Path srcPath = resolveSourcePath();
        if (srcPath == null || !Files.exists(srcPath)) {
            log.debug("Source path not available for text matching");
            return new ArrayList<>();
        }
        
        try {
            Set<String> keywords = extractKeywords(docSection);
            if (keywords.isEmpty()) {
                return new ArrayList<>();
            }
            
            log.debug("Text matching with keywords: {}", keywords);
            
            List<ScoredChunk> scoredChunks = new ArrayList<>();
            
            try (Stream<Path> paths = Files.walk(srcPath)) {
                paths.filter(Files::isRegularFile)
                     .filter(this::isSourceCodeFile)
                     .forEach(file -> {
                         try {
                             String content = Files.readString(file, StandardCharsets.UTF_8);
                             int score = calculateTextMatchScore(content, file.toString(), keywords);
                             
                             if (score > 0) {
                                 String relativePath = getRelativePathFromSrc(srcPath, file);
                                 String language = detectLanguage(file);
                                 
                                 // 提取最相关的代码片段（包含关键词最多的连续区域）
                                 String relevantSnippet = extractRelevantSnippet(content, keywords);
                                 
                                 CodeChunk chunk = CodeChunk.builder()
                                         .id(relativePath)
                                         .content(relevantSnippet)
                                         .filePath(relativePath)
                                         .language(language)
                                         .symbol(extractMainSymbol(content))
                                         .build();
                                 
                                 scoredChunks.add(new ScoredChunk(chunk, score));
                             }
                         } catch (IOException e) {
                             log.debug("Failed to read file for text matching: {}", file, e);
                         }
                     });
            }
            
            // 按得分降序排序，取 topK
            List<CodeChunk> result = scoredChunks.stream()
                    .sorted(Comparator.comparingInt(ScoredChunk::getScore).reversed())
                    .limit(topK)
                    .map(ScoredChunk::getChunk)
                    .collect(Collectors.toList());
            
            log.info("Retrieved {} relevant code chunks via text matching for: {}", result.size(), docSection);
            return result;
            
        } catch (IOException e) {
            log.error("Failed to perform text matching for: {}", docSection, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 检索与指定文件相关的代码片段
     *
     * @param filePath 文件路径
     * @param topK     返回结果数量
     * @return 相关代码片段列表
     */
    public List<CodeChunk> retrieveRelatedToFile(String filePath, int topK) {
        if (isVectorSearchAvailable()) {
            return retrieveRelatedToFileByVector(filePath, topK);
        }
        
        return retrieveRelatedToFileByTextMatch(filePath, topK);
    }
    
    /**
     * 通过向量检索与文件相关的代码片段
     */
    private List<CodeChunk> retrieveRelatedToFileByVector(String filePath, int topK) {
        try {
            String query = "相关文件: " + filePath;
            float[] queryVector = embeddingProvider.embed(query).block();
            
            if (queryVector == null) {
                return retrieveRelatedToFileByTextMatch(filePath, topK);
            }
            
            List<VectorStore.SearchResult> results = vectorStore.search(queryVector, topK, null).block();
            
            if (results == null) {
                return new ArrayList<>();
            }
            
            return results.stream()
                    .map(VectorStore.SearchResult::getChunk)
                    .filter(chunk -> !chunk.getFilePath().equals(filePath))
                    .collect(Collectors.toList());
            
        } catch (Exception e) {
            log.error("Vector search failed for related file: {}, falling back to text match", filePath, e);
            return retrieveRelatedToFileByTextMatch(filePath, topK);
        }
    }
    
    /**
     * 通过文本匹配检索与文件相关的代码片段
     * <p>
     * 从文件名和路径中提取关键词（如类名、包名），
     * 在其他源文件中搜索引用了这些关键词的代码。
     */
    private List<CodeChunk> retrieveRelatedToFileByTextMatch(String filePath, int topK) {
        Path srcPath = resolveSourcePath();
        if (srcPath == null || !Files.exists(srcPath)) {
            return new ArrayList<>();
        }
        
        try {
            // 从文件路径提取关键词（类名、包名片段等）
            Set<String> keywords = extractKeywordsFromFilePath(filePath);
            if (keywords.isEmpty()) {
                return new ArrayList<>();
            }
            
            List<ScoredChunk> scoredChunks = new ArrayList<>();
            
            try (Stream<Path> paths = Files.walk(srcPath)) {
                paths.filter(Files::isRegularFile)
                     .filter(this::isSourceCodeFile)
                     .filter(file -> !file.toString().endsWith(filePath) && 
                                     !file.getFileName().toString().equals(Path.of(filePath).getFileName().toString()))
                     .forEach(file -> {
                         try {
                             String content = Files.readString(file, StandardCharsets.UTF_8);
                             int score = calculateTextMatchScore(content, file.toString(), keywords);
                             
                             if (score > 0) {
                                 String relativePath = getRelativePathFromSrc(srcPath, file);
                                 String language = detectLanguage(file);
                                 String relevantSnippet = extractRelevantSnippet(content, keywords);
                                 
                                 CodeChunk chunk = CodeChunk.builder()
                                         .id(relativePath)
                                         .content(relevantSnippet)
                                         .filePath(relativePath)
                                         .language(language)
                                         .symbol(extractMainSymbol(content))
                                         .build();
                                 
                                 scoredChunks.add(new ScoredChunk(chunk, score));
                             }
                         } catch (IOException e) {
                             log.debug("Failed to read file: {}", file, e);
                         }
                     });
            }
            
            return scoredChunks.stream()
                    .sorted(Comparator.comparingInt(ScoredChunk::getScore).reversed())
                    .limit(topK)
                    .map(ScoredChunk::getChunk)
                    .collect(Collectors.toList());
            
        } catch (IOException e) {
            log.error("Text match failed for related file: {}", filePath, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 向量化 Wiki 文档内容（用于后续搜索）
     * <p>
     * 仅在 EmbeddingProvider 可用时执行向量化索引。
     *
     * @param wikiPath Wiki 目录路径
     */
    public void indexWikiDocuments(Path wikiPath) {
        if (!isVectorSearchAvailable()) {
            log.debug("VectorStore or EmbeddingProvider not available, skipping wiki vector indexing");
            return;
        }
        
        try {
            log.info("Indexing wiki documents from: {}", wikiPath);
            
            Files.walk(wikiPath)
                 .filter(Files::isRegularFile)
                 .filter(p -> p.getFileName().toString().endsWith(".md"))
                 .forEach(this::indexWikiDocument);
            
            log.info("Wiki documents indexed successfully");
            
        } catch (IOException e) {
            log.error("Failed to index wiki documents", e);
        }
    }
    
    /**
     * 向量化单个 Wiki 文档
     */
    private void indexWikiDocument(Path docPath) {
        try {
            String content = Files.readString(docPath, StandardCharsets.UTF_8);
            String relativePath = getRelativePath(docPath);
            
            List<String> paragraphs = splitIntoParagraphs(content);
            
            int chunkIndex = 0;
            for (String paragraph : paragraphs) {
                if (paragraph.trim().length() < 50) {
                    continue;
                }
                
                float[] embedding = embeddingProvider.embed(paragraph).block();
                
                if (embedding == null) {
                    continue;
                }
                
                Map<String, String> metadata = new HashMap<>();
                metadata.put(WIKI_METADATA_KEY, "true");
                metadata.put("doc_path", relativePath);
                
                CodeChunk chunk = CodeChunk.builder()
                        .id(relativePath + "#chunk-" + chunkIndex)
                        .content(paragraph)
                        .filePath(relativePath)
                        .language("markdown")
                        .embedding(embedding)
                        .metadata(metadata)
                        .build();
                
                vectorStore.add(chunk).block();
                chunkIndex++;
            }
            
            log.debug("Indexed wiki document: {} ({} chunks)", relativePath, chunkIndex);
            
        } catch (Exception e) {
            log.error("Failed to index wiki document: {}", docPath, e);
        }
    }
    
    /**
     * 搜索 Wiki 文档
     * <p>
     * 优先使用向量搜索；当 EmbeddingProvider 不可用时，降级为文本关键词匹配。
     *
     * @param query 查询文本
     * @param topK  返回结果数量
     * @return Wiki 搜索结果列表
     */
    public List<WikiSearchResult> searchWiki(String query, int topK) {
        if (isVectorSearchAvailable()) {
            return searchWikiByVector(query, topK);
        }
        
        log.debug("EmbeddingProvider not available, falling back to text-based wiki search");
        return searchWikiByTextMatch(query, topK);
    }
    
    /**
     * 通过向量搜索 Wiki 文档
     */
    private List<WikiSearchResult> searchWikiByVector(String query, int topK) {
        try {
            float[] queryVector = embeddingProvider.embed(query).block();
            
            if (queryVector == null) {
                return searchWikiByTextMatch(query, topK);
            }
            
            List<VectorStore.SearchResult> results = vectorStore.search(queryVector, topK).block();
            
            if (results != null) {
                results = results.stream()
                        .filter(r -> r.getChunk().getMetadata() != null && 
                                     "true".equals(r.getChunk().getMetadata().get(WIKI_METADATA_KEY)))
                        .collect(Collectors.toList());
            }
            
            if (results == null || results.isEmpty()) {
                return new ArrayList<>();
            }
            
            return results.stream()
                    .map(this::toWikiSearchResult)
                    .collect(Collectors.toList());
            
        } catch (Exception e) {
            log.error("Vector-based wiki search failed, falling back to text match", e);
            return searchWikiByTextMatch(query, topK);
        }
    }
    
    /**
     * 通过文本关键词匹配搜索 Wiki 文档
     * <p>
     * 扫描 Wiki 目录下的 Markdown 文件，按关键词命中率排序返回结果。
     */
    private List<WikiSearchResult> searchWikiByTextMatch(String query, int topK) {
        Path wikiPath = resolveWikiPath();
        if (wikiPath == null || !Files.exists(wikiPath)) {
            log.debug("Wiki path not available for text-based search");
            return new ArrayList<>();
        }
        
        try {
            Set<String> keywords = extractKeywords(query);
            if (keywords.isEmpty()) {
                return new ArrayList<>();
            }
            
            List<WikiSearchResult> results = new ArrayList<>();
            
            try (Stream<Path> paths = Files.walk(wikiPath)) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> p.getFileName().toString().endsWith(".md"))
                     .forEach(docFile -> {
                         try {
                             String content = Files.readString(docFile, StandardCharsets.UTF_8);
                             int score = calculateTextMatchScore(content, docFile.toString(), keywords);
                             
                             if (score > 0) {
                                 String relativePath = getRelativePath(docFile);
                                 String relevantSnippet = extractRelevantSnippet(content, keywords);
                                 
                                 results.add(WikiSearchResult.builder()
                                         .docPath(relativePath)
                                         .content(relevantSnippet)
                                         .score(score / 100.0)
                                         .build());
                             }
                         } catch (IOException e) {
                             log.debug("Failed to read wiki file: {}", docFile, e);
                         }
                     });
            }
            
            return results.stream()
                    .sorted(Comparator.comparingDouble(WikiSearchResult::getScore).reversed())
                    .limit(topK)
                    .collect(Collectors.toList());
            
        } catch (IOException e) {
            log.error("Text-based wiki search failed", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 转换为 Wiki 搜索结果
     */
    private WikiSearchResult toWikiSearchResult(VectorStore.SearchResult searchResult) {
        CodeChunk chunk = searchResult.getChunk();
        String docPath = chunk.getMetadata().get("doc_path");
        
        return WikiSearchResult.builder()
                .docPath(docPath)
                .content(chunk.getContent())
                .score(searchResult.getScore())
                .build();
    }
    
    /**
     * 格式化代码片段为 Markdown
     */
    public String formatCodeChunks(List<CodeChunk> chunks) {
        if (chunks.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n### 相关代码片段\n\n");
        
        for (int i = 0; i < chunks.size(); i++) {
            CodeChunk chunk = chunks.get(i);
            sb.append(String.format("#### %d. %s\n\n", i + 1, chunk.getFilePath()));
            
            if (chunk.getSymbol() != null) {
                sb.append(String.format("**符号**: `%s`\n\n", chunk.getSymbol()));
            }
            
            sb.append("```").append(chunk.getLanguage()).append("\n");
            sb.append(chunk.getContent());
            sb.append("\n```\n\n");
        }
        
        return sb.toString();
    }
    
    // ==================== 文本匹配辅助方法 ====================
    
    /**
     * 从查询文本中提取关键词
     * <p>
     * 策略：按空格和标点分词，过滤停用词和过短的词，保留中英文关键词。
     */
    private Set<String> extractKeywords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptySet();
        }
        
        String[] tokens = text.toLowerCase().split("[\\s\\p{Punct}]+");
        Set<String> keywords = new LinkedHashSet<>();
        
        for (String token : tokens) {
            if (token.length() < 2 || isStopWord(token)) {
                continue;
            }
            keywords.add(token);
        }
        
        // 提取中文短语（2-4个连续汉字）
        java.util.regex.Matcher matcher = Pattern.compile("[\\u4e00-\\u9fa5]{2,4}").matcher(text);
        while (matcher.find()) {
            keywords.add(matcher.group());
        }
        
        return keywords;
    }
    
    /**
     * 从文件路径中提取关键词
     * <p>
     * 提取类名、包名片段等作为搜索关键词。
     */
    private Set<String> extractKeywordsFromFilePath(String filePath) {
        Set<String> keywords = new LinkedHashSet<>();
        
        String fileName = Path.of(filePath).getFileName().toString();
        // 去掉扩展名
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            fileName = fileName.substring(0, dotIndex);
        }
        
        // 按驼峰拆分类名
        String[] camelParts = fileName.split("(?=[A-Z])");
        for (String part : camelParts) {
            if (part.length() >= 2) {
                keywords.add(part.toLowerCase());
            }
        }
        
        // 完整类名也作为关键词
        keywords.add(fileName.toLowerCase());
        keywords.add(fileName);
        
        return keywords;
    }
    
    /**
     * 计算文本匹配得分
     * <p>
     * 计分策略：
     * - 文件名包含关键词：+30分/词
     * - 类/接口声明包含关键词：+20分/词
     * - 文件内容包含关键词：+5分/次（最多计10次）
     */
    private int calculateTextMatchScore(String content, String filePath, Set<String> keywords) {
        int score = 0;
        String contentLower = content.toLowerCase();
        String filePathLower = filePath.toLowerCase();
        
        for (String keyword : keywords) {
            String keywordLower = keyword.toLowerCase();
            
            // 文件名匹配（高权重）
            if (filePathLower.contains(keywordLower)) {
                score += 30;
            }
            
            // 类/接口声明匹配（中权重）
            if (contentLower.contains("class " + keywordLower) || 
                contentLower.contains("interface " + keywordLower)) {
                score += 20;
            }
            
            // 内容匹配（低权重，限制计数避免长文件偏高）
            int occurrences = countOccurrences(contentLower, keywordLower);
            score += Math.min(occurrences, 10) * 5;
        }
        
        return score;
    }
    
    /**
     * 统计子串出现次数
     */
    private int countOccurrences(String text, String keyword) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(keyword, index)) != -1) {
            count++;
            index += keyword.length();
        }
        return count;
    }
    
    /**
     * 提取最相关的代码片段
     * <p>
     * 找到包含关键词最密集的连续代码区域（约30行），作为代码片段返回。
     */
    private String extractRelevantSnippet(String content, Set<String> keywords) {
        List<String> lines = content.lines().collect(Collectors.toList());
        if (lines.size() <= 30) {
            return content;
        }
        
        // 计算每行的关键词命中数
        int[] lineScores = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            String lineLower = lines.get(i).toLowerCase();
            for (String keyword : keywords) {
                if (lineLower.contains(keyword.toLowerCase())) {
                    lineScores[i]++;
                }
            }
        }
        
        // 滑动窗口找到得分最高的30行区域
        int windowSize = 30;
        int bestStart = 0;
        int bestScore = 0;
        int currentScore = 0;
        
        for (int i = 0; i < Math.min(windowSize, lines.size()); i++) {
            currentScore += lineScores[i];
        }
        bestScore = currentScore;
        
        for (int i = windowSize; i < lines.size(); i++) {
            currentScore += lineScores[i] - lineScores[i - windowSize];
            if (currentScore > bestScore) {
                bestScore = currentScore;
                bestStart = i - windowSize + 1;
            }
        }
        
        int endLine = Math.min(bestStart + windowSize, lines.size());
        return lines.subList(bestStart, endLine).stream()
                .collect(Collectors.joining("\n"));
    }
    
    /**
     * 提取文件中的主要符号名（类名或接口名）
     */
    private String extractMainSymbol(String content) {
        java.util.regex.Matcher classMatcher = Pattern.compile(
                "(public\\s+)?(class|interface|enum)\\s+(\\w+)").matcher(content);
        if (classMatcher.find()) {
            return classMatcher.group(3);
        }
        return null;
    }
    
    /**
     * 判断是否为停用词
     */
    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of(
                "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
                "of", "with", "by", "from", "is", "are", "was", "were", "be", "been",
                "this", "that", "it", "its", "not", "no", "do", "does", "did",
                "的", "了", "是", "在", "有", "和", "或", "但", "如果", "这", "那"
        );
        return stopWords.contains(word);
    }
    
    /**
     * 判断是否为源代码文件
     */
    private boolean isSourceCodeFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".kt") || 
               name.endsWith(".xml") || name.endsWith(".yml") || name.endsWith(".yaml");
    }
    
    /**
     * 检测文件编程语言
     */
    private String detectLanguage(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".java")) return "java";
        if (name.endsWith(".kt")) return "kotlin";
        if (name.endsWith(".xml")) return "xml";
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return "yaml";
        return "text";
    }
    
    // ==================== 路径解析 ====================
    
    /**
     * 解析项目源代码路径
     */
    private Path resolveSourcePath() {
        Path workDir = resolveWorkDir();
        if (workDir == null) {
            return null;
        }
        return workDir.resolve("src");
    }
    
    /**
     * 解析 Wiki 文档路径
     */
    private Path resolveWikiPath() {
        Path workDir = resolveWorkDir();
        if (workDir == null) {
            return null;
        }
        return workDir.resolve(".jimi").resolve("wiki");
    }
    
    /**
     * 解析工作目录
     */
    private Path resolveWorkDir() {
        if (configuredWorkDir != null) {
            return Path.of(configuredWorkDir);
        }
        String userDir = System.getProperty("user.dir");
        return userDir != null ? Path.of(userDir) : null;
    }
    
    /**
     * 获取相对于 src 目录的路径
     */
    private String getRelativePathFromSrc(Path srcPath, Path file) {
        try {
            return srcPath.getParent().relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }
    
    // ==================== 通用辅助方法 ====================
    
    /**
     * 将内容分割为段落
     */
    private List<String> splitIntoParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        StringBuilder currentParagraph = new StringBuilder();
        
        for (String line : content.lines().collect(Collectors.toList())) {
            if (line.trim().isEmpty()) {
                if (currentParagraph.length() > 0) {
                    paragraphs.add(currentParagraph.toString());
                    currentParagraph = new StringBuilder();
                }
            } else {
                if (currentParagraph.length() > 0) {
                    currentParagraph.append("\n");
                }
                currentParagraph.append(line);
            }
        }
        
        if (currentParagraph.length() > 0) {
            paragraphs.add(currentParagraph.toString());
        }
        
        return paragraphs;
    }
    
    /**
     * 获取相对路径
     */
    private String getRelativePath(Path path) {
        return path.getFileName().toString();
    }
    
    /**
     * 检查向量检索是否可用（需要 VectorStore + EmbeddingProvider 同时可用）
     */
    public boolean isVectorSearchAvailable() {
        return vectorStore != null && embeddingProvider != null;
    }
    
    /**
     * 检查服务是否可用（向量检索或文本匹配至少一种可用即为可用）
     */
    public boolean isAvailable() {
        return true;
    }
    
    /**
     * 带得分的代码片段（内部排序用）
     */
    private static class ScoredChunk {
        private final CodeChunk chunk;
        private final int score;
        
        ScoredChunk(CodeChunk chunk, int score) {
            this.chunk = chunk;
            this.score = score;
        }
        
        CodeChunk getChunk() {
            return chunk;
        }
        
        int getScore() {
            return score;
        }
    }
    
    /**
     * Wiki 搜索结果
     */
    @lombok.Data
    @lombok.Builder
    public static class WikiSearchResult {
        private String docPath;      // 文档路径
        private String content;       // 内容片段
        private double score;         // 相似度分数
    }
}
