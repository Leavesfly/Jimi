package io.leavesfly.jimi.core.engine.context;

import io.leavesfly.jimi.core.compaction.Compaction;
import io.leavesfly.jimi.core.engine.EngineConstants;
import io.leavesfly.jimi.knowledge.memory.MemoryManager;
import io.leavesfly.jimi.knowledge.memory.MemoryStore;
import io.leavesfly.jimi.knowledge.memory.TopicMatcher;

import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.skill.SkillRegistry;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.message.CompactionBegin;
import io.leavesfly.jimi.wire.message.CompactionEnd;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文管理器
 * <p>
 * 职责：
 * - 上下文压缩检查和执行
 * - Skill 匹配和注入
 * - Topic 文件按需加载（Layer 2 记忆）
 */
@Slf4j
@Component
public class ContextManager {
    @Autowired
    private Wire wire;
    @Autowired(required = false)
    private SkillRegistry skillRegistry;
    @Autowired
    private MemoryManager memoryManager;

    /**
     * 设置依赖（用于 Spring Bean 注入后设置依赖）
     */
    public void setWire(Wire wire) {
        this.wire = wire;
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
     * 匹配并注入相关 Topic 文件到上下文
     * <p>
     * 对标 Claude Code 的 Layer 2 读取路径：根据用户输入按需加载相关 Topic 文件。
     * 匹配到的 Topic 内容以 system 消息的形式注入到上下文中。
     *
     * @param context     上下文
     * @param workDirPath 工作目录绝对路径
     * @return 完成的 Mono
     */
    public Mono<Void> matchAndInjectTopics(Context context, String workDirPath) {
        return Mono.defer(() -> {
            if (memoryManager == null || !memoryManager.getConfig().isEnabled()) {
                return Mono.empty();
            }

            try {
                String userQuery = extractUserQuery(context);
                if (userQuery == null || userQuery.isEmpty()) {
                    return Mono.empty();
                }

                MemoryStore store = memoryManager.getOrCreateStore(workDirPath);
                TopicMatcher matcher = new TopicMatcher(store);
                List<TopicMatcher.MatchedTopic> matchedTopics = matcher.match(userQuery, 2);

                if (matchedTopics.isEmpty()) {
                    return Mono.empty();
                }

                String topicContent = matcher.loadMatchedTopics(matchedTopics);
                if (topicContent.isEmpty()) {
                    return Mono.empty();
                }

                // 以 assistant 消息注入 Topic 内容
                List<ContentPart> parts = new ArrayList<>();
                parts.add(TextPart.of("[Memory Context] " + topicContent));
                Message topicMessage = Message.builder()
                        .role(MessageRole.ASSISTANT)
                        .content(parts)
                        .build();

                log.info("Injected {} topic(s) into context: {}",
                        matchedTopics.size(),
                        matchedTopics.stream().map(TopicMatcher.MatchedTopic::topicName).toList());

                return context.appendMessage(topicMessage);
            } catch (Exception e) {
                log.warn("Failed to match and inject topics", e);
                return Mono.empty();
            }
        });
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
