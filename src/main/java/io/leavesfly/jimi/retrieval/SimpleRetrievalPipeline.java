package io.leavesfly.jimi.retrieval;

import io.leavesfly.jimi.engine.context.Context;
import io.leavesfly.jimi.engine.runtime.Runtime;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 简单检索增强管线实现
 * <p>
 * 流程：
 * 1. 从上下文提取用户查询
 * 2. 使用EmbeddingProvider生成查询向量
 * 3. 调用VectorStore进行相似度搜索
 * 4. 格式化检索结果并注入到上下文
 */
@Slf4j
public class SimpleRetrievalPipeline implements RetrievalPipeline {

    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    private final int defaultTopK;

    public SimpleRetrievalPipeline(VectorStore vectorStore, 
                                   EmbeddingProvider embeddingProvider,
                                   int defaultTopK) {
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
        this.defaultTopK = defaultTopK;
    }

    @Override
    public Mono<Integer> retrieveAndInject(Context context, Runtime runtime) {
        return Mono.defer(() -> {
            // 提取用户查询
            String query = extractUserQuery(context);
            if (query == null || query.trim().isEmpty()) {
                log.debug("No user query found, skipping retrieval");
                return Mono.just(0);
            }

            log.debug("Retrieving context for query: {}", query.substring(0, Math.min(100, query.length())));

            // 执行检索
            return retrieve(query, defaultTopK, runtime)
                    .flatMap(result -> {
                        if (result.getTotalRetrieved() == 0) {
                            log.debug("No relevant chunks found");
                            return Mono.just(0);
                        }

                        // 格式化并注入到上下文
                        List<ContentPart> contentParts = formatAsContentParts(result);
                        Message retrievalMessage = Message.builder()
                                .role(MessageRole.SYSTEM)
                                .content(contentParts)
                                .build();

                        // 注入到上下文历史
                        return context.appendMessage(retrievalMessage)
                                .thenReturn(result.getTotalRetrieved());
                    });
        });
    }

    @Override
    public Mono<RetrievalResult> retrieve(String query, int topK, Runtime runtime) {
        long startTime = System.currentTimeMillis();

        return embeddingProvider.embed(query)
                .flatMap(queryVector -> vectorStore.search(queryVector, topK))
                .map(results -> {
                    long elapsedMs = System.currentTimeMillis() - startTime;
                    
                    // 格式化检索结果
                    String formattedContent = formatResults(results);

                    return RetrievalResult.builder()
                            .query(query)
                            .results(results)
                            .totalRetrieved(results.size())
                            .elapsedMs(elapsedMs)
                            .formattedContent(formattedContent)
                            .build();
                });
    }

    @Override
    public List<ContentPart> formatAsContentParts(RetrievalResult result) {
        List<ContentPart> parts = new ArrayList<>();
        
        if (result.getTotalRetrieved() == 0) {
            return parts;
        }

        // 添加标题
        StringBuilder header = new StringBuilder();
        header.append("## Retrieved Code Context (")
              .append(result.getTotalRetrieved())
              .append(" chunks)\n\n");
        
        parts.add(TextPart.of(header.toString()));

        // 添加每个检索结果
        int index = 1;
        for (VectorStore.SearchResult searchResult : result.getResults()) {
            CodeChunk chunk = searchResult.getChunk();
            double score = searchResult.getScore();

            StringBuilder chunkText = new StringBuilder();
            chunkText.append("### Chunk ").append(index).append(": ")
                     .append(chunk.getDescription())
                     .append(" (score: ").append(String.format("%.3f", score)).append(")\n\n");
            chunkText.append("```").append(chunk.getLanguage()).append("\n");
            chunkText.append(chunk.getContent()).append("\n");
            chunkText.append("```\n\n");

            parts.add(TextPart.of(chunkText.toString()));
            index++;
        }

        return parts;
    }

    /**
     * 从上下文提取用户查询
     */
    private String extractUserQuery(Context context) {
        List<Message> history = context.getHistory();
        if (history.isEmpty()) {
            return null;
        }

        // 从最后一条用户消息中提取内容
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg.getRole() == MessageRole.USER) {
                return msg.getTextContent();
            }
        }

        return null;
    }

    /**
     * 格式化检索结果为文本
     */
    private String formatResults(List<VectorStore.SearchResult> results) {
        if (results.isEmpty()) {
            return "No relevant code chunks found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Retrieved ").append(results.size()).append(" relevant code chunks:\n\n");

        int index = 1;
        for (VectorStore.SearchResult result : results) {
            CodeChunk chunk = result.getChunk();
            sb.append(index).append(". ")
              .append(chunk.getDescription())
              .append(" (score: ").append(String.format("%.3f", result.getScore())).append(")\n");
            index++;
        }

        return sb.toString();
    }
}
