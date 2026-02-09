package io.leavesfly.jimi.adk.core.skill;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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
 * - 依赖展开：自动引入被依赖的 Skill
 */
@Slf4j
public class SkillInjector {
    
    private final SkillConfig skillConfig;
    private final SkillRegistry skillRegistry;
    private final SkillScriptExecutor scriptExecutor;
    
    /**
     * 已激活的 Skills（用于去重跟踪）
     */
    private final Set<String> activeSkillNames = Collections.synchronizedSet(new LinkedHashSet<>());
    private final List<SkillSpec> activeSkills = Collections.synchronizedList(new ArrayList<>());
    
    public SkillInjector(SkillRegistry skillRegistry, SkillConfig skillConfig, 
                         SkillScriptExecutor scriptExecutor) {
        this.skillRegistry = skillRegistry;
        this.skillConfig = skillConfig;
        this.scriptExecutor = scriptExecutor;
    }
    
    public SkillInjector(SkillRegistry skillRegistry, SkillConfig skillConfig) {
        this(skillRegistry, skillConfig, null);
    }
    
    public SkillInjector(SkillRegistry skillRegistry) {
        this(skillRegistry, null, null);
    }
    
    /**
     * 将匹配的 Skills 格式化为系统消息内容
     *
     * @param matchedSkills 匹配的 Skills 列表
     * @return 格式化的系统消息文本，如果没有新 Skill 则返回 null
     */
    public String formatSkillsForInjection(List<SkillSpec> matchedSkills) {
        return formatSkillsForInjection(matchedSkills, null);
    }
    
    /**
     * 将匹配的 Skills 格式化为系统消息内容（带工作目录用于脚本执行）
     *
     * @param matchedSkills 匹配的 Skills 列表
     * @param workDir 工作目录
     * @return 格式化的系统消息文本，如果没有新 Skill 则返回 null
     */
    public String formatSkillsForInjection(List<SkillSpec> matchedSkills, Path workDir) {
        if (matchedSkills == null || matchedSkills.isEmpty()) {
            log.debug("No skills to inject");
            return null;
        }
        
        // 展开依赖关系
        List<SkillSpec> expandedSkills = expandDependencies(matchedSkills);
        
        // 去重：过滤已激活的 Skills
        List<SkillSpec> newSkills = expandedSkills.stream()
                .filter(skill -> !activeSkillNames.contains(skill.getName()))
                .collect(Collectors.toList());
        
        if (newSkills.isEmpty()) {
            log.debug("All matched skills (including dependencies) are already active");
            return null;
        }
        
        if (logInjectionDetails()) {
            log.info("Formatting {} skills for injection:", newSkills.size());
            newSkills.forEach(skill -> 
                log.info("  - {} ({})", skill.getName(), skill.getDescription()));
        } else {
            log.info("Formatting {} skills for injection: {}", 
                    newSkills.size(),
                    newSkills.stream()
                            .map(SkillSpec::getName)
                            .collect(Collectors.joining(", ")));
        }
        
        // 格式化 Skills 为系统消息
        String skillsContent = formatSkills(newSkills);
        
        // 记录为已激活
        for (SkillSpec skill : newSkills) {
            activeSkillNames.add(skill.getName());
            activeSkills.add(skill);
        }
        
        // 执行脚本（如果有）
        executeSkillScripts(newSkills, workDir);
        
        return skillsContent;
    }
    
    /**
     * 展开 Skill 依赖关系
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
    
    /**
     * 格式化 Skills 为系统消息
     */
    private String formatSkills(List<SkillSpec> skills) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("<system>\n");
        sb.append("## Activated Skill Packs\n\n");
        sb.append("The following skill packs have been auto-activated for the current task. " +
                  "Please follow these guidelines when executing:\n\n");
        
        for (int i = 0; i < skills.size(); i++) {
            SkillSpec skill = skills.get(i);
            
            sb.append("### ").append(i + 1).append(". ").append(skill.getName()).append("\n\n");
            
            if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                sb.append("**Description**: ").append(skill.getDescription()).append("\n\n");
            }
            
            if (skill.getContent() != null && !skill.getContent().isEmpty()) {
                sb.append(skill.getContent()).append("\n\n");
            }
            
            if (skill.getScriptsPath() != null) {
                try {
                    long scriptCount = Files.list(skill.getScriptsPath()).count();
                    if (scriptCount > 0) {
                        sb.append("**Available scripts**: This Skill contains ")
                          .append(scriptCount)
                          .append(" utility scripts in `")
                          .append(skill.getScriptsPath().getFileName())
                          .append("`, invocable via Bash tool\n\n");
                    }
                } catch (Exception e) {
                    log.debug("Failed to list scripts for skill: {}", skill.getName(), e);
                }
            }
            
            if (i < skills.size() - 1) {
                sb.append("---\n\n");
            }
        }
        
        sb.append("</system>");
        
        return sb.toString();
    }
    
    /**
     * 执行 Skills 的脚本（如果有）
     */
    private void executeSkillScripts(List<SkillSpec> skills, Path workDir) {
        if (scriptExecutor == null) {
            log.debug("SkillScriptExecutor not available, skipping script execution");
            return;
        }
        
        List<SkillSpec> skillsWithScripts = skills.stream()
                .filter(skill -> skill.getScriptPath() != null && !skill.getScriptPath().isEmpty())
                .filter(SkillSpec::isAutoExecute)
                .collect(Collectors.toList());
        
        if (skillsWithScripts.isEmpty()) {
            return;
        }
        
        log.info("Executing scripts for {} skills", skillsWithScripts.size());
        
        Path effectiveWorkDir = workDir != null ? workDir : Path.of(".");
        
        for (SkillSpec skill : skillsWithScripts) {
            try {
                SkillScriptExecutor.ScriptResult result = scriptExecutor.executeScript(skill, effectiveWorkDir);
                if (result.isSuccess()) {
                    log.info("Script execution succeeded for skill '{}': {}", 
                        skill.getName(), result.getMessage());
                } else {
                    log.warn("Script execution failed for skill '{}': {}", 
                        skill.getName(), result.getMessage());
                }
            } catch (Exception e) {
                log.error("Error executing script for skill '{}'", skill.getName(), e);
            }
        }
    }
    
    /**
     * 获取当前激活的 Skills
     */
    public List<SkillSpec> getActiveSkills() {
        return Collections.unmodifiableList(new ArrayList<>(activeSkills));
    }
    
    /**
     * 检查某个 Skill 是否已激活
     */
    public boolean isSkillActive(String skillName) {
        return activeSkillNames.contains(skillName);
    }
    
    /**
     * 清理所有激活状态
     */
    public void clearActiveSkills() {
        activeSkillNames.clear();
        activeSkills.clear();
        log.debug("Cleared all active skills");
    }
    
    private boolean logInjectionDetails() {
        if (skillConfig != null && skillConfig.getLogging() != null) {
            return skillConfig.getLogging().isLogInjectionDetails();
        }
        return false;
    }
}
