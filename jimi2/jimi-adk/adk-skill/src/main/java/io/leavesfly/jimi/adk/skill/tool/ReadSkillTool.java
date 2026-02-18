package io.leavesfly.jimi.adk.skill.tool;

import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.skill.SkillInjector;
import io.leavesfly.jimi.adk.skill.SkillRegistry;
import io.leavesfly.jimi.adk.skill.SkillSpec;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ReadSkill 工具 — 逐步披露第二层（L2）
 * <p>
 * LLM 在看到系统提示中的 {@code <available_skills>} 摘要后，
 * 判断某个 Skill 与当前任务相关时，主动调用此工具加载该 Skill 的完整指令内容。
 * </p>
 * <p>
 * 功能：
 * <ul>
 *   <li>按名称查找并返回 Skill 的完整 SKILL.md 正文</li>
 *   <li>去重：已激活的 Skill 不会重复返回内容</li>
 *   <li>提示 L3 入口：如果 Skill 有 resources/scripts，提示 LLM 可进一步读取</li>
 * </ul>
 */
@Slf4j
public class ReadSkillTool implements Tool<ReadSkillTool.Params> {

    private final SkillRegistry skillRegistry;
    private final SkillInjector skillInjector;

    public ReadSkillTool(SkillRegistry skillRegistry, SkillInjector skillInjector) {
        this.skillRegistry = skillRegistry;
        this.skillInjector = skillInjector;
    }

    @Override
    public String getName() {
        return "ReadSkill";
    }

    @Override
    public String getDescription() {
        return "Load the full instructions of a skill by name. " +
               "Use this when you identify a relevant skill from <available_skills> " +
               "that matches the user's current task.";
    }

    @Override
    public Class<Params> getParamsType() {
        return Params.class;
    }

    @Override
    public Mono<ToolResult> execute(Params params) {
        if (params == null || params.getSkillName() == null || params.getSkillName().isBlank()) {
            return Mono.just(ToolResult.error(
                "Parameter 'skillName' is required. " +
                "Available skills: " + String.join(", ", skillRegistry.getAllSkillNames())));
        }

        String skillName = params.getSkillName().trim();
        log.info("ReadSkill tool invoked for skill: {}", skillName);

        // 检查是否已激活（去重）
        if (skillInjector.isSkillActive(skillName)) {
            return Mono.just(ToolResult.success(
                "Skill '" + skillName + "' is already active in this session. " +
                "Its instructions have already been loaded."));
        }

        // 查找 Skill
        Optional<SkillSpec> skillOpt = skillRegistry.findByName(skillName);
        if (skillOpt.isEmpty()) {
            String availableSkills = skillRegistry.getAllSkillNames().stream()
                    .collect(Collectors.joining(", "));
            return Mono.just(ToolResult.error(
                "Skill not found: '" + skillName + "'. " +
                "Available skills: " + availableSkills));
        }

        SkillSpec skill = skillOpt.get();

        // 构建响应
        StringBuilder response = new StringBuilder();
        response.append("## Skill: ").append(skill.getName()).append("\n\n");

        if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
            response.append("**Description**: ").append(skill.getDescription()).append("\n\n");
        }

        if (skill.getContent() != null && !skill.getContent().isEmpty()) {
            response.append(skill.getContent()).append("\n\n");
        } else {
            response.append("(This skill has no instruction content.)\n\n");
        }

        // 提示可用的辅助资源（L3 入口）
        appendResourceHints(response, skill);

        // 标记为已激活
        skillInjector.markAsActive(skill);

        log.info("ReadSkill tool loaded skill '{}' successfully", skillName);
        return Mono.just(ToolResult.success(response.toString()));
    }

    /**
     * 追加辅助资源提示信息
     */
    private void appendResourceHints(StringBuilder response, SkillSpec skill) {
        if (skill.getResourcesPath() != null) {
            try {
                String resourceFiles = Files.list(skill.getResourcesPath())
                        .filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .collect(Collectors.joining(", "));

                if (!resourceFiles.isEmpty()) {
                    response.append("**Available resources**: This skill has reference files: ")
                            .append(resourceFiles)
                            .append(". Use `ReadSkillResource` tool with skillName='")
                            .append(skill.getName())
                            .append("' to read them if needed.\n\n");
                }
            } catch (Exception e) {
                log.debug("Failed to list resources for skill: {}", skill.getName(), e);
            }
        }

        if (skill.getScriptsPath() != null) {
            try {
                long scriptCount = Files.list(skill.getScriptsPath())
                        .filter(Files::isRegularFile)
                        .count();

                if (scriptCount > 0) {
                    response.append("**Available scripts**: This skill has ")
                            .append(scriptCount)
                            .append(" executable script(s) in `")
                            .append(skill.getScriptsPath().getFileName())
                            .append("`. Use Bash tool to execute them if needed.\n\n");
                }
            } catch (Exception e) {
                log.debug("Failed to list scripts for skill: {}", skill.getName(), e);
            }
        }
    }

    /**
     * ReadSkill 工具参数
     */
    @Data
    public static class Params {
        /** 要加载的 Skill 名称（来自 {@code <available_skills>} 列表） */
        private String skillName;
    }
}
