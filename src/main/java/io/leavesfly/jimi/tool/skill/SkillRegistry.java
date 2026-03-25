package io.leavesfly.jimi.tool.skill;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Skill注册表
 * 
 * 职责：
 * - 集中管理所有已加载的Skills
 * - 提供多种查询方式（按名称、分类、触发词）
 * - 在启动时自动加载全局Skills
 * 
 * 设计特性：
 * - 线程安全：使用ConcurrentHashMap
 * - 多索引：按名称、分类、触发词建立索引以提升查询性能
 * - 优先级覆盖：项目级Skill覆盖全局Skill（同名时）
 */
@Slf4j
@Service
public class SkillRegistry {
    
    @Autowired
    private SkillLoader skillLoader;
    
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
     * 初始化加载全局Skills
     * 在Spring容器初始化时自动调用
     */
    @PostConstruct
    public void initialize() {
        log.info("Initializing SkillRegistry...");
        
        int loadedCount = 0;
        
        // 1. 先尝试从类路径加载(JAR包模式)
        List<SkillSpec> classpathSkills = skillLoader.loadSkillsFromClasspath(SkillSpec.SkillScope.GLOBAL);
        for (SkillSpec skill : classpathSkills) {
            register(skill);
            loadedCount++;
        }
        
        // 2. 加载全局Skills（从文件系统和用户目录）
        List<Path> globalDirs = skillLoader.getGlobalSkillsDirectories();
        for (Path dir : globalDirs) {
            List<SkillSpec> skills = skillLoader.loadSkillsFromDirectory(dir, SkillSpec.SkillScope.GLOBAL);
            for (SkillSpec skill : skills) {
                register(skill);
                loadedCount++;
            }
        }
        
        log.info("SkillRegistry initialized with {} global skills", loadedCount);
        
        if (loadedCount > 0) {
            log.info("Available skills: {}", 
                String.join(", ", skillsByName.keySet()));
        }
    }
    
    /**
     * 加载项目级Skills
     * 从指定的项目目录加载Skills
     * 
     * @param projectSkillsDir 项目Skills目录（如 /path/to/project/.jimi/skills）
     */
    public void loadProjectSkills(Path projectSkillsDir) {
        log.info("Loading project skills from: {}", projectSkillsDir);
        
        List<SkillSpec> skills = skillLoader.loadSkillsFromDirectory(
            projectSkillsDir, 
            SkillSpec.SkillScope.PROJECT
        );
        
        for (SkillSpec skill : skills) {
            register(skill);
        }
        
        log.info("Loaded {} project skills", skills.size());
    }
    
    /**
     * 注册一个Skill
     * 如果已存在同名Skill，会被覆盖（项目级覆盖全局级）
     * 
     * @param skill 要注册的Skill
     */
    public void register(SkillSpec skill) {
        if (skill == null || skill.getName() == null) {
            log.warn("Attempted to register invalid skill");
            return;
        }
        
        String name = skill.getName();
        
        // 检查是否覆盖
        if (skillsByName.containsKey(name)) {
            SkillSpec existing = skillsByName.get(name);
            log.info("Skill '{}' already exists (scope: {}), overriding with new skill (scope: {})",
                name, existing.getScope(), skill.getScope());
            
            // 清理旧的索引
            unregisterFromIndexes(existing);
        }
        
        // 注册到主索引
        skillsByName.put(name, skill);
        
        // 注册到分类索引
        if (skill.getCategory() != null && !skill.getCategory().isEmpty()) {
            skillsByCategory
                .computeIfAbsent(skill.getCategory(), k -> new ArrayList<>())
                .add(skill);
        }
        
        // 注册到触发词索引
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
    
    /**
     * 从索引中移除Skill
     * 
     * @param skill 要移除的Skill
     */
    private void unregisterFromIndexes(SkillSpec skill) {
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
     * 按名称查找Skill
     * 
     * @param name Skill名称
     * @return SkillSpec对象，如果不存在返回Optional.empty()
     */
    public Optional<SkillSpec> findByName(String name) {
        return Optional.ofNullable(skillsByName.get(name));
    }
    
    /**
     * 按分类查找Skills
     * 
     * @param category 分类名称
     * @return 该分类下的Skills列表（不可修改）
     */
    public List<SkillSpec> findByCategory(String category) {
        List<SkillSpec> skills = skillsByCategory.get(category);
        return skills != null ? Collections.unmodifiableList(skills) : Collections.emptyList();
    }
    
    /**
     * 根据触发词查找相关Skills
     * 支持多个关键词，返回包含任意关键词的Skills（去重）
     * 
     * @param keywords 关键词集合（会转换为小写匹配）
     * @return 匹配的Skills列表
     */
    public List<SkillSpec> findByTriggers(Set<String> keywords) {
        Set<SkillSpec> matchedSkills = new HashSet<>();
        
        for (String keyword : keywords) {
            String keywordLower = keyword.toLowerCase();
            
            // 精确匹配触发词
            List<SkillSpec> exactMatches = skillsByTrigger.get(keywordLower);
            if (exactMatches != null) {
                matchedSkills.addAll(exactMatches);
            }
            
            // 部分匹配触发词（包含关系）
            for (Map.Entry<String, List<SkillSpec>> entry : skillsByTrigger.entrySet()) {
                if (entry.getKey().contains(keywordLower) || keywordLower.contains(entry.getKey())) {
                    matchedSkills.addAll(entry.getValue());
                }
            }
        }
        
        return new ArrayList<>(matchedSkills);
    }
    
    /**
     * 获取所有已注册的Skills
     * 
     * @return 所有Skills的列表（不可修改）
     */
    public List<SkillSpec> getAllSkills() {
        return Collections.unmodifiableList(new ArrayList<>(skillsByName.values()));
    }
    
    /**
     * 获取所有Skill名称
     * 
     * @return Skill名称集合（不可修改）
     */
    public Set<String> getAllSkillNames() {
        return Collections.unmodifiableSet(skillsByName.keySet());
    }
    
    /**
     * 获取所有分类
     * 
     * @return 分类名称集合（不可修改）
     */
    public Set<String> getAllCategories() {
        return Collections.unmodifiableSet(skillsByCategory.keySet());
    }
    
    /**
     * 检查某个Skill是否已注册
     * 
     * @param name Skill名称
     * @return 是否存在
     */
    public boolean hasSkill(String name) {
        return skillsByName.containsKey(name);
    }
    
    /**
     * 获取已注册Skills的统计信息
     * 
     * @return 统计信息Map
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSkills", skillsByName.size());
        stats.put("categories", skillsByCategory.size());
        stats.put("triggers", skillsByTrigger.size());
        
        // 按作用域统计
        Map<SkillSpec.SkillScope, Long> scopeCounts = skillsByName.values().stream()
            .collect(Collectors.groupingBy(SkillSpec::getScope, Collectors.counting()));
        stats.put("globalSkills", scopeCounts.getOrDefault(SkillSpec.SkillScope.GLOBAL, 0L));
        stats.put("projectSkills", scopeCounts.getOrDefault(SkillSpec.SkillScope.PROJECT, 0L));
        
        return stats;
    }
    
    // ==================== JWork 扩展方法 ====================
    
    /**
     * 安装 Skill（从本地路径）
     * 将 Skill 复制到用户 Skills 目录并注册
     * 
     * @param skillPath Skill 目录路径（包含 SKILL.md）
     * @return 安装后的 SkillSpec
     */
    public SkillSpec install(Path skillPath) {
        log.info("Installing skill from: {}", skillPath);
        
        // 1. 加载 Skill
        SkillSpec skill = skillLoader.loadSkillFromPath(skillPath);
        if (skill == null) {
            throw new IllegalArgumentException("Invalid skill at: " + skillPath);
        }
        
        // 2. 复制到用户目录
        Path userSkillsDir = skillLoader.getUserSkillsDirectory();
        Path targetDir = userSkillsDir.resolve(skill.getName());
        
        try {
            java.nio.file.Files.createDirectories(targetDir);
            
            // 复制所有文件
            try (var stream = java.nio.file.Files.walk(skillPath)) {
                stream.forEach(source -> {
                    try {
                        Path target = targetDir.resolve(skillPath.relativize(source));
                        if (java.nio.file.Files.isDirectory(source)) {
                            java.nio.file.Files.createDirectories(target);
                        } else {
                            java.nio.file.Files.copy(source, target, 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to copy file: {}", source, e);
                    }
                });
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to install skill: " + skill.getName(), e);
        }
        
        // 3. 注册
        skill.setScope(SkillSpec.SkillScope.GLOBAL);
        register(skill);
        
        log.info("Skill installed: {}", skill.getName());
        return skill;
    }
    
    /**
     * 卸载 Skill
     * 从注册表和用户目录中移除
     * 
     * @param skillName Skill 名称
     */
    public void uninstall(String skillName) {
        log.info("Uninstalling skill: {}", skillName);
        
        SkillSpec skill = skillsByName.get(skillName);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + skillName);
        }
        
        // 只能卸载全局 Skill（用户安装的）
        if (skill.getScope() != SkillSpec.SkillScope.GLOBAL) {
            throw new IllegalArgumentException("Can only uninstall global skills: " + skillName);
        }
        
        // 1. 从注册表移除
        skillsByName.remove(skillName);
        unregisterFromIndexes(skill);
        
        // 2. 从用户目录删除
        Path userSkillsDir = skillLoader.getUserSkillsDirectory();
        Path skillDir = userSkillsDir.resolve(skillName);
        
        if (java.nio.file.Files.exists(skillDir)) {
            try {
                deleteDirectory(skillDir);
            } catch (Exception e) {
                log.warn("Failed to delete skill directory: {}", skillDir, e);
            }
        }
        
        log.info("Skill uninstalled: {}", skillName);
    }
    
    /**
     * 获取 Skill 安装信息列表（供 UI 展示）
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
     * 删除目录及其内容
     */
    private void deleteDirectory(Path dir) throws Exception {
        try (var stream = java.nio.file.Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(path -> {
                      try {
                          java.nio.file.Files.delete(path);
                      } catch (Exception e) {
                          log.warn("Failed to delete: {}", path);
                      }
                  });
        }
    }
    
    /**
     * Skill 信息（简化版，用于 UI 展示）
     */
    public record SkillInfo(
        String name,
        String description,
        String version,
        String category,
        SkillSpec.SkillScope scope
    ) {}
}
