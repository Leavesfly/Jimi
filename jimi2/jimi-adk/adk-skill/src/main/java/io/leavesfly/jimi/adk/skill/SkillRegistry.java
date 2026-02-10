package io.leavesfly.jimi.adk.skill;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Skill 注册表
 * 
 * 职责：
 * - 集中管理所有已加载的 Skills
 * - 提供多种查询方式（按名称、分类、触发词）
 * - 在启动时自动加载全局 Skills
 * 
 * 设计特性：
 * - 线程安全：使用 ConcurrentHashMap
 * - 多索引：按名称、分类、触发词建立索引
 * - 优先级覆盖：项目级 Skill 覆盖全局 Skill（同名时）
 */
@Slf4j
public class SkillRegistry {
    
    private final SkillLoader skillLoader;
    
    /** 按名称索引 */
    private final Map<String, SkillSpec> skillsByName = new ConcurrentHashMap<>();
    
    /** 按分类索引 */
    private final Map<String, List<SkillSpec>> skillsByCategory = new ConcurrentHashMap<>();
    
    /** 按触发词索引 */
    private final Map<String, List<SkillSpec>> skillsByTrigger = new ConcurrentHashMap<>();
    
    public SkillRegistry(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }
    
    /**
     * 初始化，加载全局 Skills
     * 由调用方手动调用（替代 @PostConstruct）
     */
    public void init() {
        log.info("Initializing SkillRegistry...");
        
        int loadedCount = 0;
        
        // 1. 从类路径加载（JAR 模式）
        List<SkillSpec> classpathSkills = skillLoader.loadSkillsFromClasspath(SkillScope.GLOBAL);
        for (SkillSpec skill : classpathSkills) {
            register(skill);
            loadedCount++;
        }
        
        // 2. 加载全局 Skills（文件系统和用户目录）
        List<Path> globalDirs = skillLoader.getGlobalSkillsDirectories();
        for (Path dir : globalDirs) {
            List<SkillSpec> skills = skillLoader.loadSkillsFromDirectory(dir, SkillScope.GLOBAL);
            for (SkillSpec skill : skills) {
                register(skill);
                loadedCount++;
            }
        }
        
        log.info("SkillRegistry initialized with {} global skills", loadedCount);
        
        if (loadedCount > 0) {
            log.info("Available skills: {}", String.join(", ", skillsByName.keySet()));
        }
    }
    
    /**
     * 加载项目级 Skills
     */
    public void loadProjectSkills(Path projectSkillsDir) {
        log.info("Loading project skills from: {}", projectSkillsDir);
        
        List<SkillSpec> skills = skillLoader.loadSkillsFromDirectory(
            projectSkillsDir, SkillScope.PROJECT
        );
        
        for (SkillSpec skill : skills) {
            register(skill);
        }
        
        log.info("Loaded {} project skills", skills.size());
    }
    
    /**
     * 注册一个 Skill
     */
    public void register(SkillSpec skill) {
        if (skill == null || skill.getName() == null) {
            log.warn("Attempted to register invalid skill");
            return;
        }
        
        String name = skill.getName();
        
        if (skillsByName.containsKey(name)) {
            SkillSpec existing = skillsByName.get(name);
            log.info("Skill '{}' already exists (scope: {}), overriding with new skill (scope: {})",
                name, existing.getScope(), skill.getScope());
            unregisterFromIndexes(existing);
        }
        
        skillsByName.put(name, skill);
        
        if (skill.getCategory() != null && !skill.getCategory().isEmpty()) {
            skillsByCategory
                .computeIfAbsent(skill.getCategory(), k -> new ArrayList<>())
                .add(skill);
        }
        
        if (skill.getTriggers() != null) {
            for (String trigger : skill.getTriggers()) {
                String triggerLower = trigger.toLowerCase();
                skillsByTrigger
                    .computeIfAbsent(triggerLower, k -> new ArrayList<>())
                    .add(skill);
            }
        }
        
        log.debug("Registered skill: {} (scope: {}, category: {}, triggers: {})",
            name, skill.getScope(), skill.getCategory(), 
            skill.getTriggers() != null ? skill.getTriggers().size() : 0);
    }
    
    private void unregisterFromIndexes(SkillSpec skill) {
        if (skill.getCategory() != null) {
            List<SkillSpec> categoryList = skillsByCategory.get(skill.getCategory());
            if (categoryList != null) {
                categoryList.remove(skill);
                if (categoryList.isEmpty()) {
                    skillsByCategory.remove(skill.getCategory());
                }
            }
        }
        
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
    
    public Optional<SkillSpec> findByName(String name) {
        return Optional.ofNullable(skillsByName.get(name));
    }
    
    public List<SkillSpec> findByCategory(String category) {
        List<SkillSpec> skills = skillsByCategory.get(category);
        return skills != null ? Collections.unmodifiableList(skills) : Collections.emptyList();
    }
    
    /**
     * 根据触发词查找相关 Skills
     */
    public List<SkillSpec> findByTriggers(Set<String> keywords) {
        Set<SkillSpec> matchedSkills = new HashSet<>();
        
        for (String keyword : keywords) {
            String keywordLower = keyword.toLowerCase();
            
            List<SkillSpec> exactMatches = skillsByTrigger.get(keywordLower);
            if (exactMatches != null) {
                matchedSkills.addAll(exactMatches);
            }
            
            for (Map.Entry<String, List<SkillSpec>> entry : skillsByTrigger.entrySet()) {
                if (entry.getKey().contains(keywordLower) || keywordLower.contains(entry.getKey())) {
                    matchedSkills.addAll(entry.getValue());
                }
            }
        }
        
        return new ArrayList<>(matchedSkills);
    }
    
    public List<SkillSpec> getAllSkills() {
        return Collections.unmodifiableList(new ArrayList<>(skillsByName.values()));
    }
    
    public Set<String> getAllSkillNames() {
        return Collections.unmodifiableSet(skillsByName.keySet());
    }
    
    public Set<String> getAllCategories() {
        return Collections.unmodifiableSet(skillsByCategory.keySet());
    }
    
    public boolean hasSkill(String name) {
        return skillsByName.containsKey(name);
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSkills", skillsByName.size());
        stats.put("categories", skillsByCategory.size());
        stats.put("triggers", skillsByTrigger.size());
        
        Map<SkillScope, Long> scopeCounts = skillsByName.values().stream()
            .collect(Collectors.groupingBy(SkillSpec::getScope, Collectors.counting()));
        stats.put("globalSkills", scopeCounts.getOrDefault(SkillScope.GLOBAL, 0L));
        stats.put("projectSkills", scopeCounts.getOrDefault(SkillScope.PROJECT, 0L));
        
        return stats;
    }
    
    /**
     * 注销一个 Skill（从内存索引中移除，不涉及文件系统）
     */
    public void unregister(String skillName) {
        SkillSpec removed = skillsByName.remove(skillName);
        if (removed != null) {
            unregisterFromIndexes(removed);
            log.info("Unregistered skill: {}", skillName);
        }
    }

    /**
     * 获取 Skill 安装信息列表
     */
    public List<SkillInfo> listAllInfo() {
        return skillsByName.values().stream()
            .map(spec -> new SkillInfo(
                spec.getName(),
                spec.getDescription(),
                spec.getVersion(),
                spec.getCategory(),
                spec.getScope()
            ))
            .toList();
    }
    
    /**
     * Skill 信息（简化版，用于 UI 展示）
     */
    public record SkillInfo(
        String name,
        String description,
        String version,
        String category,
        SkillScope scope
    ) {}
}
