package io.leavesfly.jimi.tool.skill;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.TextPart;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Skill 智能匹配组件
 * 
 * 职责：
 * - 分析用户输入文本，提取关键词
 * - 根据关键词从 SkillRegistry 中匹配相关 Skills
 * - 支持基于上下文历史的智能匹配
 * - 返回匹配得分最高的 Skills
 * 
 * 设计特性：
 * - 多策略匹配：支持精确匹配、部分匹配、语义匹配
 * - 去重排序：按匹配得分排序，去除重复
 * - 可配置阈值：支持设置最小匹配得分
 */
@Slf4j
@Service
public class SkillMatcher {
    
    @Autowired
    private SkillRegistry skillRegistry;
    
    @Autowired(required = false)
    private SkillConfig skillConfig;
    
    /**
     * 可选的得分调整器列表
     * 如果未注入任何实现，则不影响现有行为
     */
    @Autowired(required = false)
    private List<SkillScoreAdjuster> scoreAdjusters;
    
    /**
     * 匹配结果缓存
     * Key: 输入文本的 hash
     * Value: 匹配的 Skills 列表
     */
    private Cache<String, List<SkillSpec>> matchCache;
    
    /**
     * 默认的匹配得分阈值（0-100）
     * 低于此分数的 Skill 不会被激活
     */
    private static final int DEFAULT_SCORE_THRESHOLD = 30;
    
    /**
     * 最大返回的 Skills 数量
     */
    private static final int DEFAULT_MAX_MATCHED_SKILLS = 5;
    
    /**
     * 初始化缓存
     */
    @PostConstruct
    public void initializeCache() {
        if (isCacheEnabled()) {
            long ttl = getCacheTtl();
            int maxSize = getCacheMaxSize();
            
            matchCache = Caffeine.newBuilder()
                    .maximumSize(maxSize)
                    .expireAfterWrite(ttl, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            
            log.info("Skill match cache initialized: maxSize={}, ttl={}s", maxSize, ttl);
        } else {
            log.info("Skill match cache disabled");
        }
    }
    
    /**
     * 匹配用户输入，返回相关的 Skills
     * 
     * @param userInput 用户输入内容
     * @return 匹配的 Skills 列表（按得分降序排序）
     */
    public List<SkillSpec> matchFromInput(List<ContentPart> userInput) {
        if (userInput == null || userInput.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 提取文本内容
        String inputText = extractText(userInput);
        if (inputText.isEmpty()) {
            log.debug("No text content in user input, skipping skill matching");
            return Collections.emptyList();
        }
        
        // 尝试从缓存获取
        if (isCacheEnabled()) {
            String cacheKey = String.valueOf(inputText.hashCode());
            List<SkillSpec> cachedResult = matchCache.getIfPresent(cacheKey);
            if (cachedResult != null) {
                log.debug("Retrieved {} skills from cache for input hash: {}", cachedResult.size(), cacheKey);
                return cachedResult;
            }
        }
        
        log.debug("Matching skills for input: {}", inputText);
        
        // 执行实际匹配
        List<SkillSpec> matchedSkills = performMatch(inputText);
        
        // 缓存结果
        if (isCacheEnabled() && !matchedSkills.isEmpty()) {
            String cacheKey = String.valueOf(inputText.hashCode());
            matchCache.put(cacheKey, matchedSkills);
        }
        
        return matchedSkills;
    }
    
    /**
     * 执行实际的匹配逻辑
     */
    private List<SkillSpec> performMatch(String inputText) {
        long startTime = logPerformanceMetrics() ? System.currentTimeMillis() : 0;
        
        // 提取关键词
        Set<String> keywords = extractKeywords(inputText);
        if (keywords.isEmpty()) {
            log.debug("No keywords extracted from input");
            return Collections.emptyList();
        }
        
        if (logMatchDetails()) {
            log.debug("Extracted {} keywords: {}", keywords.size(), keywords);
        }
        
        // 从注册表中查找匹配的 Skills
        List<SkillSpec> candidateSkills = skillRegistry.findByTriggers(keywords);
        
        if (candidateSkills.isEmpty()) {
//            log.debug("No skills matched for keywords: {}", keywords);
            return Collections.emptyList();
        }
        
        // 计算匹配得分并排序
        int scoreThreshold = getScoreThreshold();
        int maxSkills = getMaxMatchedSkills();
        
        List<ScoredSkill> scoredSkills = candidateSkills.stream()
                .map(skill -> new ScoredSkill(skill, calculateScore(skill, keywords, inputText, null)))
                .filter(scored -> scored.score >= scoreThreshold)
                .sorted(Comparator.comparingInt(ScoredSkill::getScore).reversed())
                .limit(maxSkills)
                .collect(Collectors.toList());
        
        if (scoredSkills.isEmpty()) {
            log.debug("No skills exceeded score threshold: {}", scoreThreshold);
            return Collections.emptyList();
        }
        
        List<SkillSpec> matchedSkills = scoredSkills.stream()
                .map(ScoredSkill::getSkill)
                .collect(Collectors.toList());
        
        if (logPerformanceMetrics()) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Skill matching completed in {}ms: {} skills matched", elapsed, matchedSkills.size());
        } else {
            log.info("Matched {} skills: {}", matchedSkills.size(),
                    matchedSkills.stream()
                            .map(SkillSpec::getName)
                            .collect(Collectors.joining(", ")));
        }
        
        if (logMatchDetails() && !matchedSkills.isEmpty()) {
            for (int i = 0; i < matchedSkills.size(); i++) {
                ScoredSkill scored = scoredSkills.get(i);
                log.debug("  #{}: {} (score={})", i + 1, scored.getSkill().getName(), scored.getScore());
            }
        }
        
        return matchedSkills;
    }
    
    /**
     * 从上下文历史中匹配 Skills
     * 分析最近的对话消息，识别可能需要的 Skills
     * 
     * @param recentMessages 最近的消息列表（建议传入最后3-5条）
     * @return 匹配的 Skills 列表
     */
    public List<SkillSpec> matchFromContext(List<Message> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 提取所有用户和助手消息的文本
        StringBuilder contextText = new StringBuilder();
        for (Message msg : recentMessages) {
            String text = extractTextFromMessage(msg);
            if (!text.isEmpty()) {
                contextText.append(text).append(" ");
            }
        }
        
        String fullText = contextText.toString().trim();
        if (fullText.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 提取关键词
        Set<String> keywords = extractKeywords(fullText);
        if (keywords.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 查找匹配的 Skills（使用较低的阈值，因为是从上下文推断）
        List<SkillSpec> candidateSkills = skillRegistry.findByTriggers(keywords);
        
        // 计算得分并过滤（上下文匹配使用更宽松的阈值）
        final int contextThreshold = getContextScoreThreshold();
        int maxSkills = getMaxMatchedSkills();
        
        return candidateSkills.stream()
                .map(skill -> new ScoredSkill(skill, calculateScore(skill, keywords, fullText, null)))
                .filter(scored -> scored.score >= contextThreshold)
                .sorted(Comparator.comparingInt(ScoredSkill::getScore).reversed())
                .limit(maxSkills)
                .map(ScoredSkill::getSkill)
                .collect(Collectors.toList());
    }
    
    /**
     * 从 ContentPart 列表中提取纯文本
     */
    private String extractText(List<ContentPart> contentParts) {
        return contentParts.stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).getText())
                .collect(Collectors.joining(" "));
    }
    
    /**
     * 从 Message 中提取文本内容
     */
    private String extractTextFromMessage(Message message) {
        // 使用 Message 自带的 getTextContent 方法
        String text = message.getTextContent();
        return text != null ? text : "";
    }
    
    /**
     * 从文本中提取关键词
     * 
     * 策略：
     * 1. 分词（按空格、标点分割）
     * 2. 过滤停用词和短词（长度<2）
     * 3. 转换为小写
     * 4. 保留中英文关键词
     */
    private Set<String> extractKeywords(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptySet();
        }
        
        // 简单分词：按空格、标点等分割
        String[] words = text.toLowerCase()
                .split("[\\s\\p{Punct}]+");
        
        Set<String> keywords = new HashSet<>();
        
        for (String word : words) {
            // 过滤空字符串和过短的词
            if (word.isEmpty() || word.length() < 2) {
                continue;
            }
            
            // 过滤常见停用词（可扩展）
            if (isStopWord(word)) {
                continue;
            }
            
            keywords.add(word);
        }
        
        // 同时保留原始文本中的中文短语（2-4个字）
        keywords.addAll(extractChinesePhrases(text));
        
        return keywords;
    }
    
    /**
     * 提取中文短语（2-4个连续汉字）
     */
    private Set<String> extractChinesePhrases(String text) {
        Set<String> phrases = new HashSet<>();
        
        // 正则匹配2-4个连续汉字
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]{2,4}");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        
        while (matcher.find()) {
            phrases.add(matcher.group().toLowerCase());
        }
        
        return phrases;
    }
    
    /**
     * 判断是否为停用词
     * （简化版本，实际应用可以使用更完整的停用词表）
     */
    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of(
                "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
                "of", "with", "by", "from", "is", "are", "was", "were", "be", "been",
                "这", "那", "的", "了", "是", "在", "有", "和", "或", "但", "如果"
        );
        return stopWords.contains(word);
    }
    
    /**
     * 计算 Skill 的匹配得分
     * 
     * 计分策略：
     * - 触发词精确匹配：+50分
     * - 触发词部分匹配：+30分
     * - 关键词在描述中出现：+10分
     * - 名称匹配：+40分
     * 
     * @param skill 候选 Skill
     * @param keywords 提取的关键词
     * @param fullText 完整输入文本
     * @return 匹配得分（0-100）
     */
    private int calculateScore(SkillSpec skill, Set<String> keywords, String fullText, List<ContentPart> contentParts) {
        int score = 0;
        
        String fullTextLower = fullText.toLowerCase();
        String nameLower = skill.getName().toLowerCase();
        String descLower = skill.getDescription() != null 
                ? skill.getDescription().toLowerCase() 
                : "";
        
        // 检查名称匹配
        if (fullTextLower.contains(nameLower) || nameLower.contains(fullTextLower)) {
            score += 40;
        }
        
        // 检查触发词匹配
        if (skill.getTriggers() != null) {
            for (String trigger : skill.getTriggers()) {
                String triggerLower = trigger.toLowerCase();
                
                // 精确匹配
                if (keywords.contains(triggerLower) || fullTextLower.contains(triggerLower)) {
                    score += 50;
                    continue;
                }
                
                // 部分匹配
                for (String keyword : keywords) {
                    if (triggerLower.contains(keyword) || keyword.contains(triggerLower)) {
                        score += 30;
                        break;
                    }
                }
            }
        }
        
        // 检查关键词在描述中的出现
        for (String keyword : keywords) {
            if (descLower.contains(keyword)) {
                score += 10;
            }
        }
        
        int adjustedScore = score;
        if (scoreAdjusters != null && !scoreAdjusters.isEmpty()) {
            for (SkillScoreAdjuster adjuster : scoreAdjusters) {
                try {
                    adjustedScore = adjuster.adjustScore(skill, adjustedScore, keywords, fullText, contentParts);
                } catch (Exception e) {
                    log.warn("SkillScoreAdjuster {} failed, ignore it.", adjuster.getClass().getSimpleName(), e);
                }
            }
        }
        
        // 限制分数在 0-100 之间
        if (adjustedScore < 0) {
            adjustedScore = 0;
        }
        if (adjustedScore > 100) {
            adjustedScore = 100;
        }
        
        return adjustedScore;
    }
    
    /**
     * 获取匹配得分阈值（从配置或使用默认值）
     */
    private int getScoreThreshold() {
        if (skillConfig != null && skillConfig.getMatching() != null) {
            return skillConfig.getMatching().getScoreThreshold();
        }
        return DEFAULT_SCORE_THRESHOLD;
    }
    
    /**
     * 获取上下文匹配得分阈值（从配置或使用默认值）
     */
    private int getContextScoreThreshold() {
        if (skillConfig != null && skillConfig.getMatching() != null) {
            return skillConfig.getMatching().getContextScoreThreshold();
        }
        return DEFAULT_SCORE_THRESHOLD / 2;  // 默认是普通阈值的一半
    }
    
    /**
     * 获取最大匹配Skills数量（从配置或使用默认值）
     */
    private int getMaxMatchedSkills() {
        if (skillConfig != null && skillConfig.getMatching() != null) {
            return skillConfig.getMatching().getMaxMatchedSkills();
        }
        return DEFAULT_MAX_MATCHED_SKILLS;
    }
    
    /**
     * 判断是否启用缓存
     */
    private boolean isCacheEnabled() {
        if (skillConfig != null && skillConfig.getCache() != null) {
            return skillConfig.getCache().isEnabled();
        }
        return true;  // 默认启用
    }
    
    /**
     * 获取缓存TTL
     */
    private long getCacheTtl() {
        if (skillConfig != null && skillConfig.getCache() != null) {
            return skillConfig.getCache().getTtl();
        }
        return 3600;  // 默认1小时
    }
    
    /**
     * 获取缓存最大大小
     */
    private int getCacheMaxSize() {
        if (skillConfig != null && skillConfig.getCache() != null) {
            return skillConfig.getCache().getMaxSize();
        }
        return 1000;  // 默认1000条
    }
    
    /**
     * 判断是否记录匹配详情
     */
    private boolean logMatchDetails() {
        if (skillConfig != null && skillConfig.getLogging() != null) {
            return skillConfig.getLogging().isLogMatchDetails();
        }
        return false;
    }
    
    /**
     * 判断是否记录性能指标
     */
    private boolean logPerformanceMetrics() {
        if (skillConfig != null && skillConfig.getLogging() != null) {
            return skillConfig.getLogging().isLogPerformanceMetrics();
        }
        return false;
    }
    
    /**
     * 内部类：带得分的 Skill
     */
    private static class ScoredSkill {
        private final SkillSpec skill;
        private final int score;
        
        ScoredSkill(SkillSpec skill, int score) {
            this.skill = skill;
            this.score = score;
        }
        
        SkillSpec getSkill() {
            return skill;
        }
        
        int getScore() {
            return score;
        }
    }
}
