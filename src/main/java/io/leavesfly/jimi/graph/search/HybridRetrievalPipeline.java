package io.leavesfly.jimi.graph.search;

import io.leavesfly.jimi.engine.context.Context;
import io.leavesfly.jimi.engine.runtime.Runtime;
import io.leavesfly.jimi.graph.model.CodeEntity;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.retrieval.CodeChunk;
import io.leavesfly.jimi.retrieval.RetrievalPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合检索增强管线
 * <p>
 * 集成图检索和向量检索,提供增强的代码定位能力
 */
@Slf4j
@Component
public class HybridRetrievalPipeline implements RetrievalPipeline {
    
    private final HybridSearchEngine hybridSearchEngine;
    private final int defaultTopK;
    
    public HybridRetrievalPipeline(HybridSearchEngine hybridSearchEngine) {
        this.hybridSearchEngine = hybridSearchEngine;
        this.defaultTopK = 10;
    }
    
    @Override
    public Mono<Integer> retrieveAndInject(Context context, Runtime runtime) {
        // 从上下文提取用户查询
        String query = extractUserQuery(context);
        if (query == null || query.trim().isEmpty()) {
            log.debug("No user query found in context, skipping retrieval");
            return Mono.just(0);
        }
        
        // 执行混合检索
        return retrieve(query, defaultTopK, runtime)
            .flatMap(result -> {
                if (result.getTotalRetrieved() == 0) {
                    return Mono.just(0);
                }
                
                // 格式化并注入到上下文
                List<ContentPart> parts = formatAsContentParts(result);
                
                Message retrievalMessage = Message.builder()
                    .role(MessageRole.SYSTEM)
                    .content(parts)
                    .build();
                
                // 注入到上下文
                return context.appendMessage(retrievalMessage)
                    .thenReturn(result.getTotalRetrieved())
                    .doOnSuccess(count -> log.info("Injected {} hybrid search results into context", count));
            });
    }
    
    @Override
    public Mono<RetrievalResult> retrieve(String query, int topK, Runtime runtime) {
        long startTime = System.currentTimeMillis();
        
        return hybridSearchEngine.smartSearch(query, topK)
            .map(hybridResult -> {
                long elapsedMs = System.currentTimeMillis() - startTime;
                
                // 转换为 RetrievalResult
                RetrievalPipeline.RetrievalResult result = RetrievalPipeline.RetrievalResult.builder()
                    .query(query)
                    .totalRetrieved(hybridResult.getTotalResults())
                    .elapsedMs(elapsedMs)
                    .formattedContent(formatHybridResults(hybridResult))
                    .results(new ArrayList<>()) // 混合结果不填充 VectorStore.SearchResult
                    .build();
                
                return result;
            });
    }
    
    @Override
    public List<ContentPart> formatAsContentParts(RetrievalPipeline.RetrievalResult result) {
        List<ContentPart> parts = new ArrayList<>();
        
        if (result.getTotalRetrieved() == 0) {
            return parts;
        }
        
        // 使用格式化好的内容
        parts.add(TextPart.of(result.getFormattedContent()));
        
        return parts;
    }
    
    // ==================== 私有辅助方法 ====================
    
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
     * 格式化混合搜索结果
     */
    private String formatHybridResults(HybridSearchEngine.HybridSearchResult hybridResult) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("## 🔍 混合检索结果 (Hybrid Search Results)\n\n");
        sb.append(String.format("查询: %s\n", hybridResult.getQuery()));
        sb.append(String.format("找到 %d 个相关代码片段 (耗时: %dms)\n\n",
                hybridResult.getTotalResults(),
                hybridResult.getElapsedMs()));
        
        // 策略说明
        HybridSearchEngine.HybridSearchConfig config = hybridResult.getConfig();
        sb.append("**检索策略**:\n");
        sb.append(String.format("- 图检索: %s (权重: %.2f, TopK: %d)\n",
                config.isEnableGraphSearch() ? "✅" : "❌",
                config.getGraphWeight(),
                config.getGraphTopK()));
        sb.append(String.format("- 向量检索: %s (权重: %.2f, TopK: %d)\n",
                config.isEnableVectorSearch() ? "✅" : "❌",
                config.getVectorWeight(),
                config.getVectorTopK()));
        sb.append(String.format("- 融合策略: %s\n\n", config.getFusionStrategy()));
        
        // 结果详情
        int index = 1;
        for (HybridSearchEngine.HybridResult result : hybridResult.getFusedResults()) {
            sb.append(String.format("### %d. ", index));
            
            // 实体信息
            CodeEntity entity = result.getEntity();
            if (entity != null) {
                sb.append(String.format("%s: `%s`\n",
                        entity.getType(),
                        entity.getName()));
                
                if (entity.getQualifiedName() != null) {
                    sb.append(String.format("   - 完整名称: %s\n", entity.getQualifiedName()));
                }
                if (entity.getFilePath() != null) {
                    sb.append(String.format("   - 文件路径: %s", entity.getFilePath()));
                    if (entity.getStartLine() != null) {
                        sb.append(String.format(":%d", entity.getStartLine()));
                    }
                    sb.append("\n");
                }
            }
            
            // 代码片段
            CodeChunk chunk = result.getCodeChunk();
            if (chunk != null && entity == null) {
                sb.append(String.format("%s\n", chunk.getDescription()));
                sb.append(String.format("   - 文件路径: %s\n", chunk.getFilePath()));
            }
            
            // 分数信息
            sb.append(String.format("   - 融合分数: %.4f ", result.getFusedScore()));
            if (result.getGraphScore() != null) {
                sb.append(String.format("(图: %.4f", result.getGraphScore()));
                if (result.getGraphReason() != null) {
                    sb.append(String.format(" - %s", result.getGraphReason()));
                }
                sb.append(")");
            }
            if (result.getVectorScore() != null) {
                sb.append(String.format(" (向量: %.4f)", result.getVectorScore()));
            }
            sb.append("\n");
            
            // 来源标记
            if (result.getSources() != null && !result.getSources().isEmpty()) {
                sb.append("   - 来源: ");
                sb.append(result.getSources().stream()
                        .map(s -> s.name())
                        .reduce((a, b) -> a + " + " + b)
                        .orElse("UNKNOWN"));
                sb.append("\n");
            }
            
            // 代码内容 (如果有)
            if (chunk != null && chunk.getContent() != null) {
                sb.append(String.format("\n```%s\n", chunk.getLanguage() != null ? chunk.getLanguage() : ""));
                sb.append(chunk.getContent());
                sb.append("\n```\n");
            }
            
            sb.append("\n");
            index++;
        }
        
        return sb.toString();
    }
}
