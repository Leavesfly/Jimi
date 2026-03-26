package io.leavesfly.jimi.core.engine;

import io.leavesfly.jimi.core.compaction.Compaction;
import io.leavesfly.jimi.core.engine.EngineConstants;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.knowledge.KnowledgeService;
import io.leavesfly.jimi.knowledge.domain.query.UnifiedKnowledgeQuery;
import io.leavesfly.jimi.knowledge.domain.result.UnifiedKnowledgeResult;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.tool.skill.SkillRegistry;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.message.CompactionBegin;
import io.leavesfly.jimi.wire.message.CompactionEnd;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文管理器
 * <p>
 * 职责：
 * - 上下文压缩检查和执行
 * - 通过 KnowledgeService 注入 RAG 检索结果
 * - 通过 KnowledgeService 注入长期记忆
 * - Skill 匹配和注入
 */
@Slf4j
@Component
public class ContextManager {
    @Autowired
    private Wire wire;
    @Autowired(required = false)
    private SkillRegistry skillRegistry;
    @Autowired(required = false)
    private KnowledgeService knowledgeService;


    /**
     * 设置依赖（用于 Spring Bean 注入后设置依赖）
     */
    public void setWire(Wire wire) {
        this.wire = wire;
    }

    /**
     * 设置 KnowledgeService（用于 Spring Bean 注入）
     */
    public void setKnowledgeService(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 检查并压缩上下文（如果需要）
     *
     * @param context    上下文
     * @param llm        LLM 实例
     * @param compaction 压缩器
     * @return 完成的 Mono
     */
    public Mono<Void> checkAndCompact(Context context, LLM llm, Compaction compaction) {
        return Mono.defer(() -> {
            if (llm == null || compaction == null) {
                return Mono.empty();
            }

            int currentTokens = context.getTokenCount();
            int maxContextSize = llm.getMaxContextSize();

            // 检查是否需要压缩（Token 数超过限制 - 预留 Token）
            if (currentTokens > maxContextSize - EngineConstants.RESERVED_TOKENS) {
                log.info("Context size ({} tokens) approaching limit ({} tokens), triggering compaction",
                        currentTokens, maxContextSize);

                // 发送压缩开始事件
                wire.send(new CompactionBegin());

                return compaction.compact(context.getHistory(), llm)
                        .flatMap(compactedMessages -> {
                            // 回退到检查点 0（保留系统提示词和初始检查点）
                            return context.revertTo(0)
                                    .then(Mono.defer(() -> {
                                        // 添加压缩后的消息
                                        return context.appendMessage(compactedMessages);
                                    }))
                                    .doOnSuccess(v -> {
                                        log.info("Context compacted successfully");
                                        wire.send(new CompactionEnd());
                                    })
                                    .doOnError(e -> {
                                        log.error("Context compaction failed", e);
                                        wire.send(new CompactionEnd());
                                    });
                        });
            }

            return Mono.empty();
        });
    }


    /**
     * 从上下文中提取用户查询
     */
    private String extractUserQuery(Context context) {
        List<Message> history = context.getHistory();
        Message lastUser = findLastUserMessage(history);
        if (lastUser == null) {
            return null;
        }
        return lastUser.getContentParts().stream()
                .filter(p -> p instanceof TextPart)
                .map(p -> ((TextPart) p).getText())
                .collect(Collectors.joining(" "));
    }

    /**
     * 统一知识检索并注入（通过 KnowledgeService.unifiedSearch）
     * <p>
     * 整合 RAG、Graph、Memory、Wiki 四个模块的检索能力
     *
     * @param context 上下文
     * @param stepNo  当前步骤号
     * @return 完成的 Mono
     */
    public Mono<Void> matchAndInjectKnowlwdge(Context context, int stepNo) {
        // 如果没有配置 KnowledgeService，直接跳过
        if (knowledgeService == null) {
            return Mono.empty();
        }

        // 只在第一步执行检索（基于用户输入）
        if (stepNo != 1) {
            return Mono.empty();
        }

        // 从上下文中提取用户查询
        String userQuery = extractUserQuery(context);
        if (userQuery == null || userQuery.isEmpty()) {
            return Mono.empty();
        }

        // 构建统一检索查询（仅启用代码相关搜索）
        UnifiedKnowledgeQuery query = UnifiedKnowledgeQuery.builder()
                .keyword(userQuery)
                .scope(UnifiedKnowledgeQuery.SearchScope.codeOnly()) // 仅 Graph + Retrieval
                .limit(UnifiedKnowledgeQuery.ResultLimit.builder()
                        .graphLimit(5)
                        .retrievalLimit(5)
                        .build())
                .sortStrategy(UnifiedKnowledgeQuery.SortStrategy.RELEVANCE)
                .build();

        return knowledgeService.unifiedSearch(query)
                .flatMap(result -> {
                    if (result == null || !result.isSuccess() || result.getTotalResults() == 0) {
                        log.debug("No knowledge found for query: {}", userQuery);
                        return Mono.empty();
                    }

                    // 构建注入消息
                    String knowledgeContent = formatUnifiedResult(result);
                    Message knowledgeMessage = Message.user(List.of(TextPart.of(knowledgeContent)));

                    log.info("Injected unified knowledge: {} total results (Graph: {}, Retrieval: {})",
                            result.getTotalResults(),
                            result.getGraphResult().getEntityCount(),
                            result.getRetrievalResult().getChunkCount());

                    return context.appendMessage(knowledgeMessage).then();
                })
                .doOnError(e -> {
                    log.warn("Unified knowledge search failed, continuing: {}", e.getMessage());
                })
                .onErrorResume(e -> Mono.empty()) // 检索失败不影响主流程
                .then();
    }


    /**
     * 格式化统一检索结果
     */
    private String formatUnifiedResult(UnifiedKnowledgeResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## 📚 相关知识\n\n");

        // 1. 代码图谱结果
        UnifiedKnowledgeResult.GraphSearchResult graphResult = result.getGraphResult();
        if (graphResult != null && graphResult.getEntityCount() > 0) {
            sb.append("### 🔗 代码结构\n\n");
            graphResult.getEntities().forEach(entity -> {
                sb.append(String.format("- **%s** `%s` (%s)\n",
                        entity.getType(),
                        entity.getName(),
                        entity.getFilePath() != null ? entity.getFilePath() : "unknown"));
            });
            sb.append("\n");
        }

        // 2. 向量检索结果
        UnifiedKnowledgeResult.RetrievalSearchResult retrievalResult = result.getRetrievalResult();
        if (retrievalResult != null && retrievalResult.getChunkCount() > 0) {
            sb.append("### 📝 相关代码片段\n\n");
            retrievalResult.getChunks().forEach(chunk -> {
                sb.append(String.format("#### %s (lines %d-%d)\n",
                        chunk.getFilePath(), chunk.getStartLine(), chunk.getEndLine()));
                sb.append("```\n").append(chunk.getContent()).append("\n```\n\n");
            });
        }

        // 3. 长期记忆结果
        UnifiedKnowledgeResult.MemorySearchResult memoryResult = result.getMemoryResult();
        if (memoryResult != null && memoryResult.getEntryCount() > 0) {
            sb.append("### 🧠 历史记忆\n\n");
            memoryResult.getEntries().forEach(entry -> {
                sb.append(String.format("- **%s**: %s\n",
                        entry.getType() != null ? entry.getType() : "记忆",
                        entry.getContent()));
            });
            sb.append("\n");
        }

        // 4. Wiki 文档结果
        UnifiedKnowledgeResult.WikiSearchResult wikiResult = result.getWikiResult();
        if (wikiResult != null && wikiResult.getDocumentCount() > 0) {
            sb.append("### 📖 相关文档\n\n");
            wikiResult.getDocuments().forEach(doc -> {
                sb.append(String.format("#### %s\n", doc.getTitle()));
                if (doc.getSummary() != null && !doc.getSummary().isEmpty()) {
                    sb.append(doc.getSummary());
                    sb.append("\n\n");
                }
            });
        }

        return sb.toString();
    }


    /**
     * 匹配和注入 Skills（已废弃，改为渐进式披露模式）
     * 
     * 新架构：
     * - 技能摘要在 System Prompt 中提供（通过 getSkillsSummary()）
     * - 大模型通过 SkillsTool 主动调用加载完整技能内容
     * 
     * @param context 上下文
     * @param stepNo  当前步骤号
     * @return 完成的 Mono（始终为空，不再自动注入）
     */
    public Mono<Void> matchAndInjectSkills(Context context, int stepNo) {
        // 渐进式披露模式：不再自动匹配和注入技能
        // 技能摘要已在 System Prompt 中提供，大模型通过 SkillsTool 按需加载
        return Mono.empty();
    }

    /**
     * 获取技能摘要（用于 System Prompt 注入）
     * 
     * @return 技能摘要 Markdown 字符串，如果没有技能则返回空字符串
     */
    public String getSkillsSummary() {
        if (skillRegistry == null) {
            return "";
        }
        return skillRegistry.generateSkillsSummary();
    }

    /**
     * 查找最后一条用户消息
     */
    private Message findLastUserMessage(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg.getRole() == MessageRole.USER) {
                return msg;
            }
        }
        return null;
    }

}
