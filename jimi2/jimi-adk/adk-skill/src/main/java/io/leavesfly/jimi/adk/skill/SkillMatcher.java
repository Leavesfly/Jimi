package io.leavesfly.jimi.adk.skill;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
 * - 使用 ConcurrentHashMap 缓存替代 Caffeine
 */
@Slf4j
public class SkillMatcher {
    
    private final SkillRegistry skillRegistry;
    private final SkillConfig skillConfig;
    private final List<SkillScoreAdjuster> scoreAdjusters;
    
    /**
     * 匹配结果缓存（替代 Caffeine）
     */
    private final ConcurrentHashMap<String, CacheEntry> matchCache = new ConcurrentHashMap<>();
    
    private static final int DEFAULT_SCORE_THRESHOLD = 30;
    private static final int DEFAULT_MAX_MATCHED_SKILLS = 5;
    private static final int DEFAULT_CACHE_MAX_SIZE = 1000;
    
    public SkillMatcher(SkillRegistry skillRegistry, SkillConfig skillConfig, 
                        List<SkillScoreAdjuster> scoreAdjusters) {
        this.skillRegistry = skillRegistry;
        this.skillConfig = skillConfig;
        this.scoreAdjusters = scoreAdjusters != null ? scoreAdjusters : Collections.emptyList();
    }
    
    public SkillMatcher(SkillRegistry skillRegistry, SkillConfig skillConfig) {
        this(skillRegistry, skillConfig, null);
    }
    
    public SkillMatcher(SkillRegistry skillRegistry) {
        this(skillRegistry, null, null);
    }
    
    /**
     * 匹配用户输入，返回相关的 Skills
     *
     * @param inputText 用户输入文本
     * @return 匹配的 Skills 列表（按得分降序排序）
     */
    public List<SkillSpec> matchFromInput(String inputText) {
        if (inputText == null || inputText.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 尝试从缓存获取
        if (isCacheEnabled()) {
            String cacheKey = String.valueOf(inputText.hashCode());
            CacheEntry cached = matchCache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                log.debug("Retrieved {} skills from cache for input hash: {}", 
                    cached.skills.size(), cacheKey);
                return cached.skills;
            }
        }
        
        log.debug("Matching skills for input: {}", inputText);
        
        List<SkillSpec> matchedSkills = performMatch(inputText);
        
        // 缓存结果
        if (isCacheEnabled() && !matchedSkills.isEmpty()) {
            String cacheKey = String.valueOf(inputText.hashCode());
            matchCache.put(cacheKey, new CacheEntry(matchedSkills, getCacheTtl()));
            
            // 简单清理过期缓存
            if (matchCache.size() > getCacheMaxSize()) {
                cleanExpiredCache();
            }
        }
        
        return matchedSkills;
    }
    
    /**
     * 执行实际的匹配逻辑
     */
    private List<SkillSpec> performMatch(String inputText) {
        long startTime = logPerformanceMetrics() ? System.currentTimeMillis() : 0;
        
        Set<String> keywords = extractKeywords(inputText);
        if (keywords.isEmpty()) {
            log.debug("No keywords extracted from input");
            return Collections.emptyList();
        }
        
        if (logMatchDetails()) {
            log.debug("Extracted {} keywords: {}", keywords.size(), keywords);
        }
        
        List<SkillSpec> candidateSkills = skillRegistry.findByTriggers(keywords);
        
        if (candidateSkills.isEmpty()) {
            return Collections.emptyList();
        }
        
        int scoreThreshold = getScoreThreshold();
        int maxSkills = getMaxMatchedSkills();
        
        List<ScoredSkill> scoredSkills = candidateSkills.stream()
                .map(skill -> new ScoredSkill(skill, calculateScore(skill, keywords, inputText)))
                .filter(scored -> scored.score >= scoreThreshold)
                .sorted(Comparator.comparingInt(ScoredSkill::getScore).reversed())
                .limit(maxSkills)
                .collect(Collectors.toList());
        
        if (scoredSkills.isEmpty()) {
            log.debug("No skills exceeded score threshold: {}", scoreThreshold);
            return Collections.emptyList();
        }
        
        List<SkillSpec> matchedSkills = scoredSkills.stream()
                .map(s -> s.getSkill())
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
     * 从上下文文本匹配 Skills
     */
    public List<SkillSpec> matchFromContext(String contextText) {
        if (contextText == null || contextText.isEmpty()) {
            return Collections.emptyList();
        }
        
        Set<String> keywords = extractKeywords(contextText);
        if (keywords.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<SkillSpec> candidateSkills = skillRegistry.findByTriggers(keywords);
        
        final int contextThreshold = getContextScoreThreshold();
        int maxSkills = getMaxMatchedSkills();
        
        return candidateSkills.stream()
                .map(skill -> new ScoredSkill(skill, calculateScore(skill, keywords, contextText)))
                .filter(scored -> scored.score >= contextThreshold)
                .sorted(Comparator.comparingInt(ScoredSkill::getScore).reversed())
                .limit(maxSkills)
                .map(ScoredSkill::getSkill)
                .collect(Collectors.toList());
    }
    
    /**
     * 从文本中提取关键词
     */
    private Set<String> extractKeywords(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptySet();
        }
        
        String[] words = text.toLowerCase().split("[\\s\\p{Punct}]+");
        
        Set<String> keywords = new HashSet<>();
        
        for (String word : words) {
            if (word.isEmpty() || word.length() < 2) {
                continue;
            }
            if (isStopWord(word)) {
                continue;
            }
            keywords.add(word);
        }
        
        keywords.addAll(extractChinesePhrases(text));
        
        return keywords;
    }
    
    /**
     * 提取中文短语（2-4 个连续汉字）
     */
    private Set<String> extractChinesePhrases(String text) {
        Set<String> phrases = new HashSet<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]{2,4}");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            phrases.add(matcher.group().toLowerCase());
        }
        return phrases;
    }
    
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
     */
    private int calculateScore(SkillSpec skill, Set<String> keywords, String fullText) {
        int score = 0;
        
        String fullTextLower = fullText.toLowerCase();
        String nameLower = skill.getName().toLowerCase();
        String descLower = skill.getDescription() != null 
                ? skill.getDescription().toLowerCase() 
                : "";
        
        // 名称匹配 +40
        if (fullTextLower.contains(nameLower) || nameLower.contains(fullTextLower)) {
            score += 40;
        }
        
        // 触发词匹配
        if (skill.getTriggers() != null) {
            for (String trigger : skill.getTriggers()) {
                String triggerLower = trigger.toLowerCase();
                
                // 精确匹配 +50
                if (keywords.contains(triggerLower) || fullTextLower.contains(triggerLower)) {
                    score += 50;
                    continue;
                }
                
                // 部分匹配 +30
                for (String keyword : keywords) {
                    if (triggerLower.contains(keyword) || keyword.contains(triggerLower)) {
                        score += 30;
                        break;
                    }
                }
            }
        }
        
        // 关键词在描述中出现 +10
        for (String keyword : keywords) {
            if (descLower.contains(keyword)) {
                score += 10;
            }
        }
        
        // 应用得分调整器
        int adjustedScore = score;
        if (!scoreAdjusters.isEmpty()) {
            for (SkillScoreAdjuster adjuster : scoreAdjusters) {
                try {
                    adjustedScore = adjuster.adjustScore(skill, adjustedScore, keywords, fullText);
                } catch (Exception e) {
                    log.warn("SkillScoreAdjuster {} failed, ignore it.", 
                        adjuster.getClass().getSimpleName(), e);
                }
            }
        }
        
        return Math.max(0, Math.min(100, adjustedScore));
    }
    
    // ==================== 配置读取方法 ====================
    
    private int getScoreThreshold() {
        if (skillConfig != null && skillConfig.getMatching() != null) {
            return skillConfig.getMatching().getScoreThreshold();
        }
        return DEFAULT_SCORE_THRESHOLD;
    }
    
    private int getContextScoreThreshold() {
        if (skillConfig != null && skillConfig.getMatching() != null) {
            return skillConfig.getMatching().getContextScoreThreshold();
        }
        return DEFAULT_SCORE_THRESHOLD / 2;
    }
    
    private int getMaxMatchedSkills() {
        if (skillConfig != null && skillConfig.getMatching() != null) {
            return skillConfig.getMatching().getMaxMatchedSkills();
        }
        return DEFAULT_MAX_MATCHED_SKILLS;
    }
    
    private boolean isCacheEnabled() {
        if (skillConfig != null && skillConfig.getCache() != null) {
            return skillConfig.getCache().isEnabled();
        }
        return true;
    }
    
    private long getCacheTtl() {
        if (skillConfig != null && skillConfig.getCache() != null) {
            return skillConfig.getCache().getTtl();
        }
        return 3600;
    }
    
    private int getCacheMaxSize() {
        if (skillConfig != null && skillConfig.getCache() != null) {
            return skillConfig.getCache().getMaxSize();
        }
        return DEFAULT_CACHE_MAX_SIZE;
    }
    
    private boolean logMatchDetails() {
        if (skillConfig != null && skillConfig.getLogging() != null) {
            return skillConfig.getLogging().isLogMatchDetails();
        }
        return false;
    }
    
    private boolean logPerformanceMetrics() {
        if (skillConfig != null && skillConfig.getLogging() != null) {
            return skillConfig.getLogging().isLogPerformanceMetrics();
        }
        return false;
    }
    
    /**
     * 清理过期缓存
     */
    private void cleanExpiredCache() {
        matchCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
    
    // ==================== 内部类 ====================
    
    private static class ScoredSkill {
        private final SkillSpec skill;
        private final int score;
        
        ScoredSkill(SkillSpec skill, int score) {
            this.skill = skill;
            this.score = score;
        }
        
        SkillSpec getSkill() { return skill; }
        int getScore() { return score; }
    }
    
    /**
     * 缓存条目（替代 Caffeine）
     */
    private static class CacheEntry {
        final List<SkillSpec> skills;
        final long expireAt;
        
        CacheEntry(List<SkillSpec> skills, long ttlSeconds) {
            this.skills = skills;
            this.expireAt = System.currentTimeMillis() + ttlSeconds * 1000;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
