package io.leavesfly.jimi.engine.context;

import io.leavesfly.jimi.config.MemoryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 有界活动提示构建器
 * 基于 ReCAP 理念，保持提示大小 O(1)
 * 
 * 核心策略：
 * 1. Few-shot 只在顶层包含（避免每层重复）
 * 2. 始终包含高层意图
 * 3. 关键发现滑窗压缩（最近 N 条）
 * 4. 超限时头尾保留截断
 * 
 * @see <a href="https://github.com/ReCAP-Stanford/ReCAP">ReCAP: Recursive Context-Aware Reasoning and Planning</a>
 */
@Slf4j
@Component
public class ActivePromptBuilder {
    
    private final MemoryConfig config;
    
    @Autowired(required = false)
    public ActivePromptBuilder(MemoryConfig config) {
        this.config = config;
    }
    
    /**
     * 构建增强的系统提示
     * 
     * @param baseSystemPrompt 基础系统提示词
     * @param highLevelIntent 高层意图（从首条用户消息提取）
     * @param keyInsights 关键发现列表
     * @param currentDepth 当前递归深度
     * @return 增强后的提示
     */
    public String buildEnhancedPrompt(
            String baseSystemPrompt,
            String highLevelIntent,
            List<String> keyInsights,
            int currentDepth
    ) {
        StringBuilder prompt = new StringBuilder();
        
        // 1. Few-shot 只在顶层包含
        if (currentDepth == 0) {
            prompt.append(baseSystemPrompt);
        } else {
            // 子层仅包含角色定义（去除示例）
            prompt.append(extractRoleDefinition(baseSystemPrompt));
        }
        
        // 2. 高层意图始终保持
        if (highLevelIntent != null && !highLevelIntent.isEmpty()) {
            prompt.append("\n\n## 🎯 高层目标\n");
            prompt.append(highLevelIntent);
        }
        
        // 3. 关键发现（滑窗压缩）
        if (keyInsights != null && !keyInsights.isEmpty()) {
            prompt.append("\n\n## 💡 关键发现\n");
            prompt.append(compressInsights(keyInsights));
        }
        
        // 4. 截断到限制
        String result = prompt.toString();
        return truncateToLimit(result);
    }
    
    /**
     * 提取角色定义（去除 Few-shot 示例）
     * 简化版：取前 500 字符
     */
    private String extractRoleDefinition(String basePrompt) {
        if (basePrompt == null || basePrompt.isEmpty()) {
            return "";
        }
        
        return basePrompt.length() > 500 
                ? basePrompt.substring(0, 500) + "\n\n[Few-shot examples omitted at depth > 0]" 
                : basePrompt;
    }
    
    /**
     * 压缩关键发现：只保留最近 N 条
     */
    private String compressInsights(List<String> insights) {
        int windowSize = config.getInsightsWindowSize();
        int start = Math.max(0, insights.size() - windowSize);
        
        return insights.subList(start, insights.size())
                .stream()
                .map(s -> "- " + s)
                .collect(Collectors.joining("\n"));
    }
    
    /**
     * 截断到 Token 限制（保留开头和结尾）
     */
    private String truncateToLimit(String text) {
        int estimatedTokens = estimateTokens(text);
        int maxTokens = config.getActivePromptMaxTokens();
        
        if (estimatedTokens <= maxTokens) {
            return text;
        }
        
        log.warn("Prompt 超限 (估算: {} tokens, 上限: {} tokens)，执行截断", 
                estimatedTokens, maxTokens);
        
        // 简单策略：保留前 1/3 和后 2/3 的字符
        int targetChars = (int) (text.length() * maxTokens / (double) estimatedTokens);
        int headLen = targetChars / 3;
        int tailLen = targetChars * 2 / 3;
        
        return text.substring(0, headLen) 
                + "\n\n...[已截断中间内容以控制 Token 数量]...\n\n" 
                + text.substring(text.length() - tailLen);
    }
    
    /**
     * 估算 Token 数量（字符数 / 4）
     * 通用经验值，适用于中英文混合文本
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0);
    }
}
