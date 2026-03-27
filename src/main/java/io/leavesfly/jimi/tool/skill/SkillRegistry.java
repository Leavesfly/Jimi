package io.leavesfly.jimi.tool.skill;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Skill注册表
 * <p>
 * 职责：
 * - 集中管理所有已加载的Skills
 * - 提供多种查询方式（按名称、分类、触发词）
 * - 在启动时自动加载全局Skills
 * <p>
 * 设计特性：
 * - 委托SkillIndex进行索引管理
 * - 线程安全
 * - 优先级覆盖：项目级Skill覆盖全局Skill（同名时）
 */
@Slf4j
@Service
public class SkillRegistry {

    @Autowired
    private SkillLoader skillLoader;

    /**
     * 索引管理器
     */
    private final SkillIndex index = new SkillIndex();

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
                    String.join(", ", index.getAllSkillNames()));
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
        index.addToIndex(skill);
    }

    /**
     * 按名称查找Skill
     *
     * @param name Skill名称
     * @return SkillSpec对象，如果不存在返回Optional.empty()
     */
    public Optional<SkillSpec> findByName(String name) {
        return index.findByName(name);
    }

    /**
     * 按分类查找Skills
     *
     * @param category 分类名称
     * @return 该分类下的Skills列表（不可修改）
     */
    public List<SkillSpec> findByCategory(String category) {
        return index.findByCategory(category);
    }

    /**
     * 根据触发词查找相关Skills
     * 支持多个关键词，返回包含任意关键词的Skills（去重）
     *
     * @param keywords 关键词集合（会转换为小写匹配）
     * @return 匹配的Skills列表
     */
    public List<SkillSpec> findByTriggers(Set<String> keywords) {
        return index.findByTriggers(keywords);
    }

    /**
     * 获取所有已注册的Skills
     *
     * @return 所有Skills的列表（不可修改）
     */
    public List<SkillSpec> getAllSkills() {
        return index.getAllSkills();
    }

    /**
     * 获取所有Skill名称
     *
     * @return Skill名称集合（不可修改）
     */
    Set<String> getAllSkillNames() {
        return index.getAllSkillNames();
    }

    /**
     * 获取所有分类
     *
     * @return 分类名称集合（不可修改）
     */
    Set<String> getAllCategories() {
        return index.getAllCategories();
    }

    /**
     * 检查某个Skill是否已注册
     *
     * @param name Skill名称
     * @return 是否存在
     */
    public boolean hasSkill(String name) {
        return index.contains(name);
    }

    /**
     * 获取已注册Skills的统计信息
     *
     * @return 统计信息Map
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSkills", index.size());
        stats.put("categories", index.getCategoryCount());
        stats.put("triggers", index.getTriggerCount());

        // 按作用域统计
        Map<SkillSpec.SkillScope, Long> scopeCounts = index.values().stream()
                .collect(Collectors.groupingBy(SkillSpec::getScope, Collectors.counting()));
        stats.put("globalSkills", scopeCounts.getOrDefault(SkillSpec.SkillScope.GLOBAL, 0L));
        stats.put("projectSkills", scopeCounts.getOrDefault(SkillSpec.SkillScope.PROJECT, 0L));

        return stats;
    }

    // ==================== Skill 管理方法 ====================

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
            Files.createDirectories(targetDir);

            // 复制所有文件
            try (var stream = Files.walk(skillPath)) {
                stream.forEach(source -> {
                    try {
                        Path target = targetDir.resolve(skillPath.relativize(source));
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(target);
                        } else {
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
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

        SkillSpec skill = index.get(skillName);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + skillName);
        }

        // 只能卸载全局 Skill（用户安装的）
        if (skill.getScope() != SkillSpec.SkillScope.GLOBAL) {
            throw new IllegalArgumentException("Can only uninstall global skills: " + skillName);
        }

        // 1. 从注册表移除
        index.removeByName(skillName);

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
     * 创建新技能
     * 在用户 Skills 目录下创建新的 SKILL.md 文件并注册
     *
     * @param name        技能名称
     * @param description 技能描述
     * @param content     技能内容（Markdown 格式）
     * @return 创建的 SkillSpec
     */
    public SkillSpec createSkill(String name, String description, String content) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("技能内容不能为空");
        }

        // 检查是否已存在
        if (index.contains(name)) {
            throw new IllegalArgumentException("技能 '" + name + "' 已存在");
        }

        // 创建技能目录
        Path userSkillsDir = skillLoader.getUserSkillsDirectory();
        Path skillDir = userSkillsDir.resolve(name);
        Path skillFile = skillDir.resolve("SKILL.md");

        try {
            Files.createDirectories(skillDir);

            // 生成 SKILL.md 内容
            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("name: ").append(name).append("\n");
            sb.append("description: ").append(description != null ? description : "用户创建的技能").append("\n");
            sb.append("version: 1.0.0\n");
            sb.append("---\n\n");
            sb.append(content);

            Files.writeString(skillFile, sb.toString());

            // 加载并注册
            SkillSpec skill = skillLoader.parseSkillFile(skillFile);
            if (skill == null) {
                throw new RuntimeException("创建的技能文件解析失败");
            }

            skill.setScope(SkillSpec.SkillScope.GLOBAL);
            skill.setSkillFilePath(skillFile);
            register(skill);

            log.info("Created new skill: {} at {}", name, skillFile);
            return skill;

        } catch (Exception e) {
            // 清理失败的创建
            try {
                deleteDirectory(skillDir);
            } catch (Exception cleanupEx) {
                log.warn("Failed to cleanup skill directory after creation failure: {}", skillDir, cleanupEx);
            }
            throw new RuntimeException("创建技能失败: " + e.getMessage(), e);
        }
    }

    /**
     * 编辑已有技能的内容
     *
     * @param name       技能名称
     * @param newContent 新的技能内容（Markdown 格式）
     * @return 更新后的 SkillSpec
     */
    public SkillSpec editSkill(String name, String newContent) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        if (newContent == null || newContent.trim().isEmpty()) {
            throw new IllegalArgumentException("技能内容不能为空");
        }

        SkillSpec skill = index.get(name);
        if (skill == null) {
            throw new IllegalArgumentException("技能 '" + name + "' 未找到");
        }

        // 只能编辑全局技能
        if (skill.getScope() != SkillSpec.SkillScope.GLOBAL) {
            throw new IllegalArgumentException("只能编辑全局技能（用户创建的）");
        }

        Path skillFile = skill.getSkillFilePath();
        if (skillFile == null || !java.nio.file.Files.exists(skillFile)) {
            throw new RuntimeException("技能文件不存在: " + skillFile);
        }

        try {
            // 生成新的 SKILL.md 内容（保留元信息）
            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("name: ").append(skill.getName()).append("\n");
            sb.append("description: ").append(skill.getDescription()).append("\n");
            sb.append("version: ").append(skill.getVersion()).append("\n");
            if (skill.getCategory() != null && !skill.getCategory().isEmpty()) {
                sb.append("category: ").append(skill.getCategory()).append("\n");
            }
            if (skill.getTriggers() != null && !skill.getTriggers().isEmpty()) {
                sb.append("triggers:\n");
                for (String trigger : skill.getTriggers()) {
                    sb.append("  - ").append(trigger).append("\n");
                }
            }
            sb.append("---\n\n");
            sb.append(newContent);

            java.nio.file.Files.writeString(skillFile, sb.toString());

            // 重新加载技能
            SkillSpec updatedSkill = skillLoader.parseSkillFile(skillFile);
            if (updatedSkill == null) {
                throw new RuntimeException("更新后的技能文件解析失败");
            }

            updatedSkill.setScope(SkillSpec.SkillScope.GLOBAL);
            updatedSkill.setSkillFilePath(skillFile);

            // 更新注册表
            index.removeFromIndex(skill);
            index.put(name, updatedSkill);
            index.addCategoryIndex(updatedSkill);
            index.addTriggerIndex(updatedSkill);

            log.info("Updated skill: {}", name);
            return updatedSkill;

        } catch (Exception e) {
            throw new RuntimeException("编辑技能失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成技能摘要列表（用于 System Prompt 注入）
     * 引导文案与 SkillsTool.DESCRIPTION 保持一致，涵盖全部操作能力。
     *
     * @return Markdown 格式的技能摘要
     */
    public String generateSkillsSummary() {
        List<SkillSpec> skills = getAllSkills();

        if (skills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<available_skills>\n");
        sb.append("以下是已安装的技能列表。你可以通过 Skills 工具管理和调用技能：\n");
        sb.append("- invoke: 加载技能完整内容（返回完整指令和目录路径）\n");
        sb.append("- install: 安装新技能（支持 GitHub 仓库或压缩包 URL）\n");
        sb.append("- create: 创建新技能 | edit: 编辑现有技能 | remove: 删除技能\n\n");

        for (SkillSpec skill : skills) {
            sb.append("- **").append(skill.getName()).append("**");
            if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                sb.append(": ").append(skill.getDescription());
            }
            if (skill.getTriggers() != null && !skill.getTriggers().isEmpty()) {
                sb.append(" [triggers: ").append(String.join(", ", skill.getTriggers())).append("]");
            }
            sb.append("\n");
        }

        sb.append("\n当任务需要专业指导时: Skills(action='invoke', name='技能名称')\n");
        sb.append("</available_skills>");

        return sb.toString();
    }

    /**
     * 获取 Skill 安装信息列表（供 UI 展示）
     */
    public List<SkillInfo> listAllInfo() {
        return index.values().stream()
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
    ) {
    }
}
