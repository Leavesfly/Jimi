package io.leavesfly.jimi.skill;

import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.llm.message.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Skill 注入提供者
 * 
 * 职责：
 * - 将激活的 Skills 格式化为系统消息
 * - 注入 Skills 内容到上下文
 * - 管理 Skills 的生命周期（注入、更新、清理）
 * 
 * 设计特性：
 * - 格式化输出：生成清晰的 Markdown 格式 Skills 指令
 * - 去重处理：避免重复注入相同的 Skill
 * - 优先级管理：项目级 Skill 优先于全局 Skill
 */
@Slf4j
@Service
public class SkillInjector {

    
    @Autowired(required = false)
    private SkillConfig skillConfig;
    
    @Autowired
    private SkillRegistry skillRegistry;
    

    /**
     * 将匹配的 Skills 注入到上下文
     * 
     * @param context 上下文对象
     * @param matchedSkills 匹配的 Skills 列表
     * @return 注入完成的 Mono
     */
    public Mono<Void> injectSkills(Context context, List<SkillSpec> matchedSkills) {
        return injectSkills(context, matchedSkills, null);
    }
    
    /**
     * 将匹配的 Skills 注入到上下文（带工作目录）
     * 
     * @param context 上下文对象
     * @param matchedSkills 匹配的 Skills 列表
     * @param workDir 工作目录
     * @return 注入完成的 Mono
     */
    public Mono<Void> injectSkills(Context context, List<SkillSpec> matchedSkills, Path workDir) {
        if (matchedSkills == null || matchedSkills.isEmpty()) {
            log.debug("No skills to inject");
            return Mono.empty();
        }
        
        long startTime = logPerformanceMetrics() ? System.currentTimeMillis() : 0;
        
        // 展开依赖关系：支持组合/聚合型 Skill
        List<SkillSpec> expandedSkills = expandDependencies(matchedSkills);
        
        // 去重：如果某些 Skills 已经在上下文中，则跳过
        List<SkillSpec> newSkills = filterNewSkills(context, expandedSkills);
        
        if (newSkills.isEmpty()) {
            log.debug("All matched skills (including dependencies) are already active in context");
            return Mono.empty();
        }
        
        if (logInjectionDetails()) {
            log.info("Injecting {} skills into context:", newSkills.size());
            newSkills.forEach(skill -> 
                log.info("  - {} ({})", skill.getName(), skill.getDescription()));
        } else {
            log.info("Injecting {} skills into context: {}", 
                    newSkills.size(),
                    newSkills.stream()
                            .map(SkillSpec::getName)
                            .collect(Collectors.joining(", ")));
        }
        
        // 格式化 Skills 为系统消息
        String skillsContent = formatSkills(newSkills);
        
        // 创建系统消息
        Message skillsMessage = Message.system(skillsContent);
        
        // 添加到上下文并记录激活的 Skills
        return context.appendMessage(skillsMessage)
                .then(context.addActiveSkills(newSkills))
                .doOnSuccess(v -> {
                    if (logPerformanceMetrics()) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        log.info("Skill injection completed in {}ms", elapsed);
                    }
                });
    }
    
    /**
     * 过滤出新的 Skills（未激活的）
     */
    private List<SkillSpec> filterNewSkills(Context context, List<SkillSpec> matchedSkills) {
        List<String> activeSkillNames = context.getActiveSkills().stream()
                .map(SkillSpec::getName)
                .collect(Collectors.toList());
        
        return matchedSkills.stream()
                .filter(skill -> !activeSkillNames.contains(skill.getName()))
                .collect(Collectors.toList());
    }
    
    /**
     * 展开 Skill 依赖关系
     * 支持组合/聚合型 Skill 在注入前自动引入其依赖的 Skill
     */
    private List<SkillSpec> expandDependencies(List<SkillSpec> skills) {
        if (skills == null || skills.isEmpty()) {
            return skills;
        }
        
        Map<String, SkillSpec> accumulator = new LinkedHashMap<>();
        Set<String> visiting = new HashSet<>();
        
        for (SkillSpec skill : skills) {
            collectWithDependencies(skill, accumulator, visiting);
        }
        
        return List.copyOf(accumulator.values());
    }
    
    private void collectWithDependencies(SkillSpec skill,
                                         Map<String, SkillSpec> accumulator,
                                         Set<String> visiting) {
        if (skill == null || skill.getName() == null) {
            return;
        }
        String name = skill.getName();
        if (accumulator.containsKey(name)) {
            return;
        }
        if (!visiting.add(name)) {
            log.warn("Detected circular skill dependency on '{}', skipping to avoid infinite loop", name);
            return;
        }
        
        // 先处理依赖的 Skill，保证依赖在前
        List<String> dependencies = skill.getDependencies();
        if (dependencies != null) {
            for (String depName : dependencies) {
                if (depName == null || depName.isEmpty()) {
                    continue;
                }
                if (accumulator.containsKey(depName)) {
                    continue;
                }
                skillRegistry.findByName(depName).ifPresentOrElse(
                        depSkill -> collectWithDependencies(depSkill, accumulator, visiting),
                        () -> log.warn("Skill '{}' declares dependency '{}', but it was not found in registry",
                                name, depName)
                );
            }
        }
        
        accumulator.put(name, skill);
        visiting.remove(name);
    }
    private String formatSkills(List<SkillSpec> skills) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("<system>\n");
        sb.append("## 🎯 激活的技能包\n\n");
        sb.append("以下技能包已根据当前任务自动激活，请在执行任务时遵循这些专业指南：\n\n");
        
        for (int i = 0; i < skills.size(); i++) {
            SkillSpec skill = skills.get(i);
            
            // Skill 标题
            sb.append("### ").append(i + 1).append(". ").append(skill.getName()).append("\n\n");
            
            // Skill 描述
            if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                sb.append("**描述**: ").append(skill.getDescription()).append("\n\n");
            }
            
            // Skill 内容
            if (skill.getContent() != null && !skill.getContent().isEmpty()) {
                sb.append(skill.getContent()).append("\n\n");
            }
            
            // 分隔线（最后一个 Skill 不需要）
            if (i < skills.size() - 1) {
                sb.append("---\n\n");
            }
        }
        
        sb.append("</system>");
        
        return sb.toString();
    }
    
    /**
     * 清理上下文中的所有激活 Skills
     * 注意：这只会清除记录，不会删除已注入的消息
     * 
     * @param context 上下文对象
     * @return 清理完成的 Mono
     */
    public Mono<Void> clearSkills(Context context) {
        log.debug("Clearing all active skills from context");
        return context.clearActiveSkills();
    }
    
    /**
     * 获取当前上下文中激活的 Skills
     * 
     * @param context 上下文对象
     * @return 激活的 Skills 列表
     */
    public List<SkillSpec> getActiveSkills(Context context) {
        return context.getActiveSkills();
    }
    
    /**
     * 检查某个 Skill 是否已激活
     * 
     * @param context 上下文对象
     * @param skillName Skill 名称
     * @return 是否已激活
     */
    public boolean isSkillActive(Context context, String skillName) {
        return context.getActiveSkills().stream()
                .anyMatch(skill -> skill.getName().equals(skillName));
    }
    
    /**
     * 判断是否记录注入详情
     */
    private boolean logInjectionDetails() {
        if (skillConfig != null && skillConfig.getLogging() != null) {
            return skillConfig.getLogging().isLogInjectionDetails();
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
    

}
