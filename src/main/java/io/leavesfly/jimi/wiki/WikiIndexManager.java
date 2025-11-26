package io.leavesfly.jimi.wiki;

import io.leavesfly.jimi.retrieval.CodeChunk;
import io.leavesfly.jimi.retrieval.EmbeddingProvider;
import io.leavesfly.jimi.retrieval.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Wiki 索引管理器
 * <p>
 * 职责：
 * - 为文档生成提供代码检索增强
 * - 向量化 Wiki 文档内容
 * - 提供 Wiki 语义搜索能力
 */
@Slf4j
@Component
public class WikiIndexManager {
    
    @Autowired(required = false)
    private VectorStore vectorStore;
    
    @Autowired(required = false)
    private EmbeddingProvider embeddingProvider;
    
    private static final int DEFAULT_TOP_K = 5;
    private static final String WIKI_METADATA_KEY = "wiki_doc";
    
    /**
     * 检索与文档章节相关的代码片段
     *
     * @param docSection 文档章节描述（如："系统架构", "API文档"）
     * @param topK       返回结果数量
     * @return 相关代码片段列表
     */
    public List<CodeChunk> retrieveRelevantCode(String docSection, int topK) {
        if (!isAvailable()) {
            log.debug("VectorStore or EmbeddingProvider not available, skipping retrieval");
            return new ArrayList<>();
        }
        
        try {
            // 生成查询向量
            float[] queryVector = embeddingProvider.embed(docSection).block();
            
            if (queryVector == null) {
                log.warn("Failed to generate embedding for: {}", docSection);
                return new ArrayList<>();
            }
            
            // 执行向量检索（暂不使用过滤器，后续可通过 SearchFilter 优化）
            List<VectorStore.SearchResult> results = vectorStore.search(queryVector, topK).block();
            
            if (results == null || results.isEmpty()) {
                log.debug("No relevant code found for: {}", docSection);
                return new ArrayList<>();
            }
            
            log.info("Retrieved {} relevant code chunks for section: {}", results.size(), docSection);
            
            return results.stream()
                    .map(VectorStore.SearchResult::getChunk)
                    .collect(Collectors.toList());
            
        } catch (Exception e) {
            log.error("Failed to retrieve relevant code for section: {}", docSection, e);
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
        if (!isAvailable()) {
            return new ArrayList<>();
        }
        
        try {
            // 使用文件路径作为查询
            String query = "相关文件: " + filePath;
            float[] queryVector = embeddingProvider.embed(query).block();
            
            if (queryVector == null) {
                return new ArrayList<>();
            }
            
            List<VectorStore.SearchResult> results = vectorStore.search(queryVector, topK, null).block();
            
            if (results == null) {
                return new ArrayList<>();
            }
            
            return results.stream()
                    .map(VectorStore.SearchResult::getChunk)
                    .filter(chunk -> !chunk.getFilePath().equals(filePath)) // 排除自身
                    .collect(Collectors.toList());
            
        } catch (Exception e) {
            log.error("Failed to retrieve related code for file: {}", filePath, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 向量化 Wiki 文档内容（用于后续搜索）
     *
     * @param wikiPath Wiki 目录路径
     */
    public void indexWikiDocuments(Path wikiPath) {
        if (!isAvailable()) {
            log.debug("VectorStore not available, skipping wiki indexing");
            return;
        }
        
        try {
            log.info("Indexing wiki documents from: {}", wikiPath);
            
            // 遍历 Wiki 目录下的所有 Markdown 文件
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
            
            // 分块（简化版本：按段落分块）
            List<String> paragraphs = splitIntoParagraphs(content);
            
            int chunkIndex = 0;
            for (String paragraph : paragraphs) {
                if (paragraph.trim().length() < 50) {
                    continue; // 跳过太短的段落
                }
                
                // 生成向量
                float[] embedding = embeddingProvider.embed(paragraph).block();
                
                if (embedding == null) {
                    continue;
                }
                
                // 创建 CodeChunk（复用数据结构）
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
                
                // 添加到向量存储
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
     *
     * @param query 查询文本
     * @param topK  返回结果数量
     * @return Wiki 搜索结果列表
     */
    public List<WikiSearchResult> searchWiki(String query, int topK) {
        if (!isAvailable()) {
            log.warn("VectorStore not available for wiki search");
            return new ArrayList<>();
        }
        
        try {
            // 生成查询向量
            float[] queryVector = embeddingProvider.embed(query).block();
            
            if (queryVector == null) {
                return new ArrayList<>();
            }
            
            // 执行搜索（后续可通过 SearchFilter 过滤 Wiki 文档）
            List<VectorStore.SearchResult> results = vectorStore.search(queryVector, topK).block();
            
            // 手动过滤：仅保留 Wiki 文档
            if (results != null) {
                results = results.stream()
                        .filter(r -> r.getChunk().getMetadata() != null && 
                                     "true".equals(r.getChunk().getMetadata().get(WIKI_METADATA_KEY)))
                        .collect(Collectors.toList());
            }
            
            if (results == null || results.isEmpty()) {
                return new ArrayList<>();
            }
            
            // 转换为 WikiSearchResult
            return results.stream()
                    .map(this::toWikiSearchResult)
                    .collect(Collectors.toList());
            
        } catch (Exception e) {
            log.error("Failed to search wiki", e);
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
     * 检查服务是否可用
     */
    public boolean isAvailable() {
        return vectorStore != null && embeddingProvider != null;
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
