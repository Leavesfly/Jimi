package io.leavesfly.jimi.core.engine.context;

import io.leavesfly.jimi.core.compaction.Compaction;
import io.leavesfly.jimi.core.engine.EngineConstants;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.knowledge.memory.MemoryManager;

import io.leavesfly.jimi.knowledge.memory.ProjectInsight;
import io.leavesfly.jimi.knowledge.memory.SessionSummary;
import io.leavesfly.jimi.knowledge.memory.TaskHistory;
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
 * - 通过 MemoryManager 注入长期记忆
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
    private MemoryManager memoryManager;

    /**
     * 设置依赖（用于 Spring Bean 注入后设置依赖）
     */
    public void setWire(Wire wire) {
        this.wire = wire;
    }

    /**
     * 设置 MemoryManager（用于 Spring Bean 注入）
     */
    public void setMemoryManager(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
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
     * 获取长期记忆摘要（用于 System Prompt 注入）
     * 从 MemoryManager 查询最近会话、任务历史、项目知识，格式化为结构化文本
     *
     * @return 记忆摘要字符串，如果记忆未启用或无数据则返回空字符串
     */
    public String getMemorySummary() {
        if (memoryManager == null || !memoryManager.isEnabled()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<long_term_memory>\n");
        sb.append("以下是从长期记忆中检索到的历史信息，可帮助你更好地理解项目上下文和用户偏好：\n\n");

        boolean hasContent = false;

        // 最近一次会话摘要
        try {
            SessionSummary lastSession = memoryManager.getLastSession().block();
            if (lastSession != null) {
                hasContent = true;
                sb.append("## 上次会话\n");
                sb.append(lastSession.toShortSummary()).append("\n\n");
            }
        } catch (Exception e) {
            log.debug("Failed to load last session summary: {}", e.getMessage());
        }

        // 最近的任务历史
        try {
            List<TaskHistory> recentTasks = memoryManager.getRecentTasks(5).block();
            if (recentTasks != null && !recentTasks.isEmpty()) {
                hasContent = true;
                sb.append("## 最近任务\n");
                for (TaskHistory task : recentTasks) {
                    sb.append("- ").append(task.getUserQuery());
                    if (task.getSummary() != null && !task.getSummary().isEmpty()) {
                        sb.append(" → ").append(task.getSummary());
                    }
                    sb.append(" [").append(task.getResultStatus()).append("]\n");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("Failed to load recent tasks: {}", e.getMessage());
        }

        // 项目知识
        try {
            List<ProjectInsight> insights = memoryManager.queryInsights("", 5).block();
            if (insights != null && !insights.isEmpty()) {
                hasContent = true;
                sb.append("## 项目知识\n");
                for (ProjectInsight insight : insights) {
                    sb.append("- [").append(insight.getCategory()).append("] ")
                            .append(insight.getContent()).append("\n");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("Failed to load project insights: {}", e.getMessage());
        }

        if (!hasContent) {
            return "";
        }

        sb.append("</long_term_memory>");
        return sb.toString();
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
