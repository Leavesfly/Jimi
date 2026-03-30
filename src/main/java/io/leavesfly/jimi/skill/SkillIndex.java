package io.leavesfly.jimi.skill;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Skill索引管理器
 * 
 * 职责：
 * - 管理Skill的多维度索引（名称、分类、触发词）
 * - 提供高效的查询能力
 * - 线程安全的索引更新
 * 
 * 设计特性：
 * - 内部辅助类，由SkillRegistry组合使用
 * - 线程安全：使用ConcurrentHashMap
 * - 优化的触发词匹配：先精确后模糊
 */
@Slf4j
class SkillIndex {
    
    /**
     * 按名称索引的Skills
     * Key: Skill名称
     * Value: SkillSpec对象
     */
    private final Map<String, SkillSpec> skillsByName = new ConcurrentHashMap<>();
    
    /**
     * 按分类索引的Skills
     * Key: 分类名称
     * Value: 该分类下的Skills列表
     */
    private final Map<String, List<SkillSpec>> skillsByCategory = new ConcurrentHashMap<>();
    
    /**
     * 按触发词索引的Skills
     * Key: 触发词（小写）
     * Value: 包含该触发词的Skills列表
     */
    private final Map<String, List<SkillSpec>> skillsByTrigger = new ConcurrentHashMap<>();
    
    /**
     * 添加Skill到索引
     * 如果已存在同名Skill，会先移除旧索引
     * 
     * @param skill 要添加的Skill
     * @return 被覆盖的旧Skill，如果不存在返回null
     */
    SkillSpec addToIndex(SkillSpec skill) {
        if (skill == null || skill.getName() == null) {
            log.warn("Attempted to index invalid skill");
            return null;
        }
        
        String name = skill.getName();
        SkillSpec existing = null;
        
        // 检查是否覆盖
        if (skillsByName.containsKey(name)) {
            existing = skillsByName.get(name);
            log.info("Skill '{}' already exists (scope: {}), overriding with new skill (scope: {})",
                name, existing.getScope(), skill.getScope());
            
            // 清理旧的索引
            removeFromIndex(existing);
        }
        
        // 注册到主索引
        skillsByName.put(name, skill);
        
        // 注册到分类索引
        if (skill.getCategory() != null && !skill.getCategory().isEmpty()) {
            skillsByCategory
                .computeIfAbsent(skill.getCategory(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(skill);
        }
        
        // 注册到触发词索引
        if (skill.getTriggers() != null) {
            for (String trigger : skill.getTriggers()) {
                String triggerLower = trigger.toLowerCase();
                skillsByTrigger
                    .computeIfAbsent(triggerLower, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(skill);
            }
        }
        
        log.debug("Indexed skill: {} (scope: {}, category: {}, triggers: {})",
            name, skill.getScope(), skill.getCategory(), 
            skill.getTriggers() != null ? skill.getTriggers().size() : 0);
        
        return existing;
    }
    
    /**
     * 从索引中移除Skill
     * 
     * @param skill 要移除的Skill
     */
    void removeFromIndex(SkillSpec skill) {
        if (skill == null) {
            return;
        }
        
        // 从分类索引移除
        if (skill.getCategory() != null) {
            List<SkillSpec> categoryList = skillsByCategory.get(skill.getCategory());
            if (categoryList != null) {
                categoryList.remove(skill);
                if (categoryList.isEmpty()) {
                    skillsByCategory.remove(skill.getCategory());
                }
            }
        }
        
        // 从触发词索引移除
        if (skill.getTriggers() != null) {
            for (String trigger : skill.getTriggers()) {
                String triggerLower = trigger.toLowerCase();
                List<SkillSpec> triggerList = skillsByTrigger.get(triggerLower);
                if (triggerList != null) {
                    triggerList.remove(skill);
                    if (triggerList.isEmpty()) {
                        skillsByTrigger.remove(triggerLower);
                    }
                }
            }
        }
    }
    
    /**
     * 从主索引中移除并清理所有相关索引
     * 
     * @param name Skill名称
     * @return 被移除的Skill，如果不存在返回null
     */
    SkillSpec removeByName(String name) {
        SkillSpec skill = skillsByName.remove(name);
        if (skill != null) {
            removeFromIndex(skill);
        }
        return skill;
    }
    
    /**
     * 按名称查找Skill
     * 
     * @param name Skill名称
     * @return SkillSpec对象，如果不存在返回Optional.empty()
     */
    Optional<SkillSpec> findByName(String name) {
        return Optional.ofNullable(skillsByName.get(name));
    }
    
    /**
     * 按名称获取Skill（直接返回，可能为null）
     * 
     * @param name Skill名称
     * @return SkillSpec对象或null
     */
    SkillSpec get(String name) {
        return skillsByName.get(name);
    }
    
    /**
     * 按分类查找Skills
     * 
     * @param category 分类名称
     * @return 该分类下的Skills列表（不可修改）
     */
    List<SkillSpec> findByCategory(String category) {
        List<SkillSpec> skills = skillsByCategory.get(category);
        return skills != null ? Collections.unmodifiableList(new ArrayList<>(skills)) : Collections.emptyList();
    }
    
    /**
     * 根据触发词查找相关Skills
     * 支持多个关键词，返回包含任意关键词的Skills（去重）
     * 
     * 优化策略：
     * 1. 先进行精确匹配（O(1) HashMap lookup）
     * 2. 再进行模糊匹配（跳过已精确匹配的key）
     * 3. 使用Stream去重
     * 
     * @param keywords 关键词集合（会转换为小写匹配）
     * @return 匹配的Skills列表
     */
    List<SkillSpec> findByTriggers(Set<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 转换为小写的关键词集合
        Set<String> keywordsLower = keywords.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
        
        // 精确匹配结果
        Set<String> exactMatchedKeys = new HashSet<>();
        Stream<SkillSpec> exactMatches = keywordsLower.stream()
            .filter(keyword -> {
                boolean hasExact = skillsByTrigger.containsKey(keyword);
                if (hasExact) {
                    exactMatchedKeys.add(keyword);
                }
                return hasExact;
            })
            .flatMap(keyword -> skillsByTrigger.get(keyword).stream());
        
        // 模糊匹配（跳过已精确匹配的key，避免重复）
        Stream<SkillSpec> fuzzyMatches = skillsByTrigger.entrySet().stream()
            .filter(entry -> {
                String triggerKey = entry.getKey();
                // 跳过已精确匹配的key
                if (exactMatchedKeys.contains(triggerKey)) {
                    return false;
                }
                // 检查是否有任意关键词与此trigger存在包含关系
                return keywordsLower.stream()
                    .anyMatch(keyword -> triggerKey.contains(keyword) || keyword.contains(triggerKey));
            })
            .flatMap(entry -> entry.getValue().stream());
        
        // 合并并去重
        return Stream.concat(exactMatches, fuzzyMatches)
            .distinct()
            .collect(Collectors.toList());
    }
    
    /**
     * 获取所有已注册的Skills
     * 
     * @return 所有Skills的列表（不可修改）
     */
    List<SkillSpec> getAllSkills() {
        return Collections.unmodifiableList(new ArrayList<>(skillsByName.values()));
    }
    
    /**
     * 获取所有Skill名称
     * 
     * @return Skill名称集合（不可修改）
     */
    Set<String> getAllSkillNames() {
        return Collections.unmodifiableSet(new HashSet<>(skillsByName.keySet()));
    }
    
    /**
     * 获取所有分类
     * 
     * @return 分类名称集合（不可修改）
     */
    Set<String> getAllCategories() {
        return Collections.unmodifiableSet(new HashSet<>(skillsByCategory.keySet()));
    }
    
    /**
     * 检查某个Skill是否已注册
     * 
     * @param name Skill名称
     * @return 是否存在
     */
    boolean contains(String name) {
        return skillsByName.containsKey(name);
    }
    
    /**
     * 获取已注册Skills数量
     * 
     * @return Skills数量
     */
    int size() {
        return skillsByName.size();
    }
    
    /**
     * 获取分类数量
     */
    int getCategoryCount() {
        return skillsByCategory.size();
    }
    
    /**
     * 获取触发词数量
     */
    int getTriggerCount() {
        return skillsByTrigger.size();
    }
    
    /**
     * 获取所有Skills的values集合
     * 用于统计等操作
     */
    Collection<SkillSpec> values() {
        return skillsByName.values();
    }
    
    /**
     * 直接将Skill放入主索引（不建立其他索引）
     * 用于editSkill等需要精确控制索引的场景
     * 
     * @param name Skill名称
     * @param skill SkillSpec对象
     */
    void put(String name, SkillSpec skill) {
        skillsByName.put(name, skill);
    }
    
    /**
     * 为Skill添加分类索引
     */
    void addCategoryIndex(SkillSpec skill) {
        if (skill.getCategory() != null && !skill.getCategory().isEmpty()) {
            skillsByCategory
                .computeIfAbsent(skill.getCategory(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(skill);
        }
    }
    
    /**
     * 为Skill添加触发词索引
     */
    void addTriggerIndex(SkillSpec skill) {
        if (skill.getTriggers() != null) {
            for (String trigger : skill.getTriggers()) {
                String triggerLower = trigger.toLowerCase();
                skillsByTrigger
                    .computeIfAbsent(triggerLower, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(skill);
            }
        }
    }
}
