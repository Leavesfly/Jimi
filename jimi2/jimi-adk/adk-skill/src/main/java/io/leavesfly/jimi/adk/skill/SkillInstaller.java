package io.leavesfly.jimi.adk.skill;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/**
 * Skill 安装/卸载器
 * <p>
 * 职责：处理 Skill 的文件系统安装和卸载操作。
 * 从 {@link SkillRegistry} 中提取，使注册表只关注内存中的注册与查找。
 * </p>
 */
@Slf4j
public class SkillInstaller {

    private final SkillLoader skillLoader;
    private final SkillRegistry skillRegistry;

    public SkillInstaller(SkillLoader skillLoader, SkillRegistry skillRegistry) {
        this.skillLoader = skillLoader;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 安装 Skill（从本地路径复制到用户 Skills 目录并注册）
     *
     * @param skillPath Skill 来源路径
     * @return 已安装的 SkillSpec
     */
    public SkillSpec install(Path skillPath) {
        log.info("Installing skill from: {}", skillPath);

        SkillSpec skill = skillLoader.loadSkillFromPath(skillPath);
        if (skill == null) {
            throw new IllegalArgumentException("Invalid skill at: " + skillPath);
        }

        Path userSkillsDir = skillLoader.getUserSkillsDirectory();
        Path targetDir = userSkillsDir.resolve(skill.getName());

        try {
            Files.createDirectories(targetDir);

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

        skill.setScope(SkillScope.GLOBAL);
        skillRegistry.register(skill);

        log.info("Skill installed: {}", skill.getName());
        return skill;
    }

    /**
     * 卸载 Skill（从用户 Skills 目录删除并注销注册）
     *
     * @param skillName Skill 名称
     */
    public void uninstall(String skillName) {
        log.info("Uninstalling skill: {}", skillName);

        SkillSpec skill = skillRegistry.findByName(skillName)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillName));

        if (skill.getScope() != SkillScope.GLOBAL) {
            throw new IllegalArgumentException("Can only uninstall global skills: " + skillName);
        }

        skillRegistry.unregister(skillName);

        Path userSkillsDir = skillLoader.getUserSkillsDirectory();
        Path skillDir = userSkillsDir.resolve(skillName);

        if (Files.exists(skillDir)) {
            try {
                deleteDirectory(skillDir);
            } catch (Exception e) {
                log.warn("Failed to delete skill directory: {}", skillDir, e);
            }
        }

        log.info("Skill uninstalled: {}", skillName);
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(Path dir) throws Exception {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (Exception e) {
                            log.warn("Failed to delete: {}", path);
                        }
                    });
        }
    }
}
