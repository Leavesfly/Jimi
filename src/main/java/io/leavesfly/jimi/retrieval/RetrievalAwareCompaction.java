package io.leavesfly.jimi.retrieval;

import io.leavesfly.jimi.engine.compaction.Compaction;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 检索感知的上下文压缩
 * <p>
 * 在压缩时保留项目知识脉络：
 * 1. 执行常规压缩（使用基础Compaction）
 * 2. 提取压缩后的关键概念
 * 3. 从向量索引检索相关代码片段
 * 4. 将检索结果作为"项目知识"注入到压缩后的上下文
 * <p>
 * 优势：
 * - 长会话中保持对项目结构的理解
 * - 避免遗忘重要的代码上下文
 * - 压缩与检索融合，优化上下文利用率
 */
@Slf4j
public class RetrievalAwareCompaction implements Compaction {

    private final Compaction baseCompaction;
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    private final int topK;
    private final boolean enabled;

    /**
     * 构造函数
     *
     * @param baseCompaction 基础压缩实现
     * @param vectorStore 向量存储
     * @param embeddingProvider 嵌入提供者
     * @param topK 检索片段数量
     * @param enabled 是否启用检索增强（可配置关闭）
     */
    public RetrievalAwareCompaction(Compaction baseCompaction,
                                    VectorStore vectorStore,
                                    EmbeddingProvider embeddingProvider,
                                    int topK,
                                    boolean enabled) {
        this.baseCompaction = baseCompaction;
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
        this.topK = topK;
        this.enabled = enabled;
        
        log.info("RetrievalAwareCompaction initialized: enabled={}, topK={}", enabled, topK);
    }

    @Override
    public Mono<List<Message>> compact(List<Message> history, LLM llm) {
        if (!enabled || vectorStore == null || embeddingProvider == null) {
            log.debug("Retrieval-aware compaction disabled, using base compaction");
            return baseCompaction.compact(history, llm);
        }

        return baseCompaction.compact(history, llm)
                .flatMap(compactedMessages -> {
                    if (compactedMessages.isEmpty()) {
                        return Mono.just(compactedMessages);
                    }

                    log.debug("Enhancing compacted context with retrieved knowledge");

                    // 提取压缩后的关键概念
                    String compactedText = extractTextFromMessages(compactedMessages);
                    
                    if (compactedText.trim().isEmpty()) {
                        return Mono.just(compactedMessages);
                    }

                    // 检索相关代码片段
                    return retrieveRelevantCode(compactedText)
                            .map(retrievalMessage -> {
                                if (retrievalMessage == null) {
                                    return compactedMessages;
                                }

                                // 将检索结果插入到压缩消息之后
                                List<Message> enhanced = new ArrayList<>();
                                enhanced.addAll(compactedMessages);
                                enhanced.add(retrievalMessage);

                                log.info("Enhanced compacted context with retrieved project knowledge");
                                return enhanced;
                            })
                            .onErrorResume(e -> {
                                log.warn("Failed to retrieve code context during compaction: {}", 
                                        e.getMessage());
                                return Mono.just(compactedMessages);
                            });
                });
    }

    /**
     * 从消息列表提取文本
     */
    private String extractTextFromMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            String text = msg.getTextContent();
            if (text != null && !text.isEmpty()) {
                sb.append(text).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 检索相关代码片段
     */
    private Mono<Message> retrieveRelevantCode(String query) {
        return embeddingProvider.embed(query)
                .flatMap(queryVector -> vectorStore.search(queryVector, topK))
                .map(results -> {
                    if (results.isEmpty()) {
                        return null;
                    }

                    // 格式化检索结果为系统消息
                    List<ContentPart> parts = new ArrayList<>();
                    
                    StringBuilder header = new StringBuilder();
                    header.append("## 📚 项目知识脉络 (Compaction Context)\n\n");
                    header.append("以下代码片段是根据压缩后的上下文自动检索的关键项目知识：\n\n");
                    parts.add(TextPart.of(header.toString()));

                    int index = 1;
                    for (VectorStore.SearchResult result : results) {
                        CodeChunk chunk = result.getChunk();
                        
                        StringBuilder chunkText = new StringBuilder();
                        chunkText.append("### ").append(index).append(". ")
                                 .append(chunk.getDescription())
                                 .append("\n\n");
                        chunkText.append("```").append(chunk.getLanguage()).append("\n");
                        chunkText.append(chunk.getContent()).append("\n");
                        chunkText.append("```\n\n");

                        parts.add(TextPart.of(chunkText.toString()));
                        index++;
                    }

                    return Message.builder()
                            .role(MessageRole.SYSTEM)
                            .content(parts)
                            .build();
                });
    }
}
