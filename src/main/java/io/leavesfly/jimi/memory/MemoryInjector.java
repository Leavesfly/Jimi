package io.leavesfly.jimi.memory;

import io.leavesfly.jimi.config.MemoryConfig;
import io.leavesfly.jimi.engine.context.Context;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.TextPart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆注入器
 * 在执行前将相关知识注入到上下文中
 */
@Slf4j
@Component
public class MemoryInjector {
    
    private final MemoryManager memoryManager;
    private final MemoryConfig config;
    
    public MemoryInjector(MemoryManager memoryManager, MemoryConfig config) {
        this.memoryManager = memoryManager;
        this.config = config;
    }
    
    /**
     * 在执行前注入相关记忆到上下文
     * 
     * @param context 上下文
     * @param userQuery 用户查询
     * @return 完成的 Mono
     */
    public Mono<Void> injectMemories(Context context, String userQuery) {
        // 检查是否启用且设置为自动注入
        if (!config.isLongTermEnabled() || !config.isAutoInject()) {
            return Mono.empty();
        }
        
        if (userQuery == null || userQuery.isEmpty()) {
            return Mono.empty();
        }
        
        return Mono.defer(() -> {
            // 检测是否是任务历史查询
            if (isTaskHistoryQuery(userQuery)) {
                return injectTaskHistory(context, userQuery);
            }
            
            // 否则执行普通的知识注入
            return injectProjectInsights(context, userQuery);
        });
    }
    
    /**
     * 检测是否为任务历史查询
     */
    private boolean isTaskHistoryQuery(String query) {
        String lowerQuery = query.toLowerCase();
        return lowerQuery.contains("最近做") || 
               lowerQuery.contains("做了什么") ||
               lowerQuery.contains("之前做") ||
               lowerQuery.contains("历史任务") ||
               lowerQuery.contains("最近的任务") ||
               lowerQuery.contains("昨天做") ||
               lowerQuery.contains("上次做") ||
               lowerQuery.contains("recent task") ||
               lowerQuery.contains("what did") ||
               lowerQuery.contains("task history");
    }
    
    /**
     * 注入任务历史到上下文
     */
    private Mono<Void> injectTaskHistory(Context context, String userQuery) {
        return Mono.defer(() -> {
            // 判断是否是时间范围查询
            if (userQuery.contains("昨天")) {
                Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
                Instant today = Instant.now();
                return memoryManager.getTasksByTimeRange(yesterday, today)
                        .flatMap(tasks -> injectTasksToContext(context, tasks, "昨天的任务"));
            } else if (userQuery.contains("上次") || userQuery.contains("最近一次")) {
                return memoryManager.getRecentTasks(1)
                        .flatMap(tasks -> injectTasksToContext(context, tasks, "最近一次任务"));
            } else {
                // 默认返回最近 5 条任务
                return memoryManager.getRecentTasks(5)
                        .flatMap(tasks -> injectTasksToContext(context, tasks, "最近的任务"));
            }
        });
    }
    
    /**
     * 将任务列表注入到上下文
     */
    private Mono<Void> injectTasksToContext(Context context, List<TaskHistory> tasks, String title) {
        if (tasks.isEmpty()) {
            String noTaskMsg = String.format("## 📝 %s\n\n没有找到相关的任务历史。\n", title);
            return context.appendMessage(Message.user(List.of(TextPart.of(noTaskMsg))));
        }
        
        String historyPrompt = buildTaskHistoryPrompt(tasks, title);
        log.info("注入 {} 条任务历史到上下文", tasks.size());
        return context.appendMessage(Message.user(List.of(TextPart.of(historyPrompt))));
    }
    
    /**
     * 构建任务历史提示
     */
    private String buildTaskHistoryPrompt(List<TaskHistory> tasks, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 📝 ").append(title).append("\n\n");
        
        for (int i = 0; i < tasks.size(); i++) {
            TaskHistory task = tasks.get(i);
            sb.append(task.formatForDisplay());
            if (i < tasks.size() - 1) {
                sb.append("\n---\n");
            }
        }
        
        sb.append("\n\n以上是任务历史记录。\n");
        return sb.toString();
    }
    
    /**
     * 注入项目知识到上下文
     * 优先使用语义检索（如果启用），否则回退到关键词
     */
    private Mono<Void> injectProjectInsights(Context context, String userQuery) {
        // 如果启用了语义检索，直接使用完整查询语句
        if (memoryManager.isSemanticSearchEnabled()) {
            return memoryManager.queryInsights(userQuery, 3)  // 语义检索用完整查询
                    .flatMap(insights -> injectInsightsToContext(context, insights, userQuery));
        }
        
        // 关键词检索模式：提取关键词
        List<String> keywords = extractKeywords(userQuery);
        if (keywords.isEmpty()) {
            return Mono.empty();
        }
        
        String primaryKeyword = keywords.get(0);
        return memoryManager.queryInsights(primaryKeyword, 3)
                .flatMap(insights -> injectInsightsToContext(context, insights, primaryKeyword));
    }
    
    /**
     * 将知识注入到上下文
     */
    private Mono<Void> injectInsightsToContext(Context context, List<ProjectInsight> insights, String query) {
        if (insights.isEmpty()) {
            log.debug("未找到与 [{}] 相关的知识", query);
            return Mono.empty();
        }
        
        String memoryPrompt = buildMemoryPrompt(insights);
        Message memoryMsg = Message.user(List.of(TextPart.of(memoryPrompt)));
        
        log.info("注入 {} 条相关知识到上下文{}", insights.size(),
                memoryManager.isSemanticSearchEnabled() ? " (语义检索)" : "");
        return context.appendMessage(memoryMsg);
    }
    
    /**
     * 构建记忆注入提示
     * 
     * @param insights 知识列表
     * @return 格式化的提示文本
     */
    private String buildMemoryPrompt(List<ProjectInsight> insights) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 📚 相关项目知识\n\n");
        sb.append("以下是从历史会话中积累的相关知识，供参考：\n\n");
        
        for (int i = 0; i < insights.size(); i++) {
            ProjectInsight insight = insights.get(i);
            sb.append(String.format("%d. **[%s]** %s\n", 
                    i + 1,
                    formatCategory(insight.getCategory()), 
                    insight.getContent()));
            
            // 添加来源和置信度信息（可选）
            if (log.isDebugEnabled()) {
                sb.append(String.format("   _(来源: %s, 置信度: %.2f, 访问: %d次)_\n", 
                        insight.getSource(),
                        insight.getConfidence(),
                        insight.getAccessCount()));
            }
            sb.append("\n");
        }
        
        sb.append("请结合上述知识完成任务。如果知识已过时或不适用，请忽略。\n");
        
        return sb.toString();
    }
    
    /**
     * 格式化分类名称（转换为友好显示）
     * 
     * @param category 分类
     * @return 格式化后的分类名
     */
    private String formatCategory(String category) {
        return switch (category) {
            case "architecture" -> "架构";
            case "code_structure" -> "代码结构";
            case "code_search" -> "代码搜索";
            case "execution" -> "执行结果";
            case "code_creation" -> "代码创建";
            case "code_modification" -> "代码修改";
            case "subagent_task" -> "子任务";
            case "bug_fix" -> "Bug修复";
            default -> category;
        };
    }
    
    /**
     * 提取关键词（简单分词 + 过滤）
     * 
     * @param text 文本
     * @return 关键词列表
     */
    private List<String> extractKeywords(String text) {
        // 停用词列表
        List<String> stopWords = Arrays.asList(
                "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这",
                "请", "帮", "我", "给", "把", "让", "下",
                "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by", "from", "as", "is", "was", "are", "were", "be", "been", "being", "have", "has", "had", "do", "does", "did", "will", "would", "should", "could", "may", "might", "can", "must"
        );
        
        // 简单分词：按空格和标点符号分割
        return Arrays.stream(text.split("[\\s\\p{Punct}]+"))
                .map(String::toLowerCase)
                .filter(word -> word.length() > 2)  // 过滤短词
                .filter(word -> !stopWords.contains(word))  // 过滤停用词
                .limit(3)  // 最多3个关键词
                .collect(Collectors.toList());
    }
}
