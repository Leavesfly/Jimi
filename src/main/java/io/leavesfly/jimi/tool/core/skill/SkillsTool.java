package io.leavesfly.jimi.tool.core.skill;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.tool.skill.SkillRegistry;
import io.leavesfly.jimi.tool.skill.SkillSpec;
import io.leavesfly.jimi.tool.skill.SkillsInstaller;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 技能管理工具，赋予 Agent 自主学习和管理技能的能力。
 * 
 * 这是实现 AI 渐进式披露 Skill 的核心工具，让 Agent 能够：
 * 1. 列出可用技能摘要（list）
 * 2. 按需加载技能完整内容（invoke）
 * 3. 从 GitHub 或 URL 安装技能（install）
 * 4. 创建新技能固化经验（create）
 * 5. 编辑已有技能优化内容（edit）
 * 6. 删除不需要的技能（remove）
 * 
 * 设计理念：
 * - 渐进式披露：初始只提供摘要，按需加载完整内容
 * - 大模型主导：由 LLM 决定何时需要加载技能
 * - 自主学习：AI 可以创建和优化技能
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class SkillsTool extends AbstractTool<SkillsTool.Params> {

    private static final String NAME = "Skills";
    private static final String DESCRIPTION = 
            "管理和调用技能：列出所有技能（list）、加载技能完整内容（invoke）、" +
            "安装技能（install，支持 GitHub 仓库或压缩包 URL）、创建新技能（create）、" +
            "编辑现有技能（edit）或删除技能（remove）。" +
            "使用 invoke 操作加载技能时，会返回技能的完整指令内容和所在目录路径。";

    @Autowired
    private SkillRegistry skillRegistry;
    
    @Autowired(required = false)
    private SkillsInstaller skillsInstaller;

    public SkillsTool() {
        super(NAME, DESCRIPTION, Params.class);
    }

    @Override
    public Mono<ToolResult> execute(Params params) {
        return Mono.fromCallable(() -> {
            if (params.getAction() == null || params.getAction().isEmpty()) {
                return ToolResult.error(
                        "action 参数是必需的。有效操作：list、invoke、install、create、edit、remove",
                        "缺少参数"
                );
            }

            try {
                return switch (params.getAction().toLowerCase()) {
                    case "list" -> executeList();
                    case "invoke" -> executeInvoke(params);
                    case "install" -> executeInstall(params);
                    case "create" -> executeCreate(params);
                    case "edit" -> executeEdit(params);
                    case "remove" -> executeRemove(params);
                    default -> ToolResult.error(
                            "未知操作: " + params.getAction() + "。有效操作：list、invoke、install、create、edit、remove",
                            "无效操作"
                    );
                };
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage(), "参数错误");
            } catch (Exception e) {
                log.error("执行技能操作失败: {}", params.getAction(), e);
                return ToolResult.error(
                        "执行操作 '" + params.getAction() + "' 失败: " + e.getMessage(),
                        "执行失败"
                );
            }
        });
    }

    /**
     * 列出所有已安装的技能
     */
    private ToolResult executeList() {
        List<SkillSpec> skills = skillRegistry.getAllSkills();
        
        if (skills.isEmpty()) {
            return ToolResult.ok(
                    "没有安装任何技能。\n\n您可以：\n" +
                    "- 从 GitHub 安装：使用 action='install' 和 repo='owner/repo'\n" +
                    "- 创建新技能：使用 action='create' 并指定 name、description 和 content",
                    "无技能"
            );
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 已安装的技能 (").append(skills.size()).append(" 个)\n\n");

        for (SkillSpec skill : skills) {
            sb.append("- **").append(skill.getName()).append("**");
            if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                sb.append(": ").append(skill.getDescription());
            }
            if (skill.getCategory() != null && !skill.getCategory().isEmpty()) {
                sb.append(" [").append(skill.getCategory()).append("]");
            }
            if (skill.getTriggers() != null && !skill.getTriggers().isEmpty()) {
                sb.append("\n  触发词: ").append(String.join(", ", skill.getTriggers()));
            }
            sb.append("\n");
        }

        sb.append("\n使用 `action='invoke' name='技能名称'` 加载技能的完整内容。");

        return ToolResult.ok(sb.toString(), "列出 " + skills.size() + " 个技能");
    }

    /**
     * 调用/加载技能完整内容
     */
    private ToolResult executeInvoke(Params params) {
        String name = requireParam(params.getName(), "name", "invoke");

        Optional<SkillSpec> skillOpt = skillRegistry.findByName(name);
        if (skillOpt.isEmpty()) {
            return ToolResult.error(
                    "技能 '" + name + "' 未找到。使用 action='list' 查看所有可用技能。",
                    "技能未找到"
            );
        }

        SkillSpec skill = skillOpt.get();
        StringBuilder sb = new StringBuilder();

        // 技能元信息
        sb.append("## 技能: ").append(skill.getName()).append("\n\n");
        if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
            sb.append("**描述**: ").append(skill.getDescription()).append("\n\n");
        }
        if (skill.getVersion() != null) {
            sb.append("**版本**: ").append(skill.getVersion()).append("\n\n");
        }

        // 技能所在目录（用于执行脚本）
        Path skillPath = skill.getSkillFilePath();
        if (skillPath != null) {
            Path basePath = skillPath.getParent();
            if (basePath != null) {
                sb.append("**目录路径**: `").append(basePath.toAbsolutePath()).append("`\n\n");
            }
        }

        // 技能完整内容
        sb.append("---\n\n");
        if (skill.getContent() != null && !skill.getContent().isEmpty()) {
            sb.append(skill.getContent());
        } else {
            sb.append("*技能内容为空*");
        }

        return ToolResult.ok(sb.toString(), "已加载技能: " + name);
    }

    /**
     * 安装技能
     */
    private ToolResult executeInstall(Params params) {
        String repo = requireParam(params.getRepo(), "repo", "install");

        if (skillsInstaller == null) {
            return ToolResult.error(
                    "技能安装器不可用。请检查配置。",
                    "安装器不可用"
            );
        }

        try {
            SkillSpec installed;
            
            // 判断是 URL 还是 GitHub repo
            if (repo.startsWith("http://") || repo.startsWith("https://")) {
                // URL 安装（压缩包）
                String skillName = params.getName(); // 可选的技能名称
                installed = skillsInstaller.installFromUrl(repo, skillName);
            } else {
                // GitHub 仓库安装
                installed = skillsInstaller.installFromGitHub(repo);
            }

            if (installed == null) {
                return ToolResult.error(
                        "安装技能失败: 无法从 '" + repo + "' 解析技能",
                        "安装失败"
                );
            }

            return ToolResult.ok(
                    String.format("技能 '%s' 安装成功！\n\n描述: %s\n版本: %s",
                            installed.getName(),
                            installed.getDescription(),
                            installed.getVersion()),
                    "安装成功: " + installed.getName()
            );
        } catch (Exception e) {
            log.error("安装技能失败: {}", repo, e);
            return ToolResult.error(
                    "安装技能失败: " + e.getMessage(),
                    "安装失败"
            );
        }
    }

    /**
     * 创建新技能
     */
    private ToolResult executeCreate(Params params) {
        String name = requireParam(params.getName(), "name", "create");
        String content = requireParam(params.getContent(), "content", "create");
        String description = params.getDescription();
        
        if (description == null || description.isEmpty()) {
            description = "用户创建的技能";
        }

        // 检查是否已存在
        if (skillRegistry.hasSkill(name)) {
            return ToolResult.error(
                    "技能 '" + name + "' 已存在。使用 action='edit' 修改现有技能，或使用其他名称。",
                    "技能已存在"
            );
        }

        try {
            SkillSpec created = skillRegistry.createSkill(name, description, content);
            
            return ToolResult.ok(
                    String.format("技能 '%s' 创建成功！\n\n路径: %s",
                            created.getName(),
                            created.getSkillFilePath()),
                    "创建成功: " + name
            );
        } catch (Exception e) {
            log.error("创建技能失败: {}", name, e);
            return ToolResult.error(
                    "创建技能失败: " + e.getMessage(),
                    "创建失败"
            );
        }
    }

    /**
     * 编辑已有技能
     */
    private ToolResult executeEdit(Params params) {
        String name = requireParam(params.getName(), "name", "edit");
        String content = requireParam(params.getContent(), "content", "edit");

        // 检查技能是否存在
        if (!skillRegistry.hasSkill(name)) {
            return ToolResult.error(
                    "技能 '" + name + "' 未找到。使用 action='list' 查看所有可用技能。",
                    "技能未找到"
            );
        }

        try {
            SkillSpec edited = skillRegistry.editSkill(name, content);
            
            return ToolResult.ok(
                    String.format("技能 '%s' 更新成功！", edited.getName()),
                    "编辑成功: " + name
            );
        } catch (Exception e) {
            log.error("编辑技能失败: {}", name, e);
            return ToolResult.error(
                    "编辑技能失败: " + e.getMessage(),
                    "编辑失败"
            );
        }
    }

    /**
     * 删除技能
     */
    private ToolResult executeRemove(Params params) {
        String name = requireParam(params.getName(), "name", "remove");

        // 检查技能是否存在
        Optional<SkillSpec> skillOpt = skillRegistry.findByName(name);
        if (skillOpt.isEmpty()) {
            return ToolResult.error(
                    "技能 '" + name + "' 未找到。使用 action='list' 查看所有可用技能。",
                    "技能未找到"
            );
        }

        SkillSpec skill = skillOpt.get();
        
        // 只能删除用户安装的全局技能
        if (skill.getScope() != SkillSpec.SkillScope.GLOBAL) {
            return ToolResult.error(
                    "只能删除全局技能（用户安装的）。技能 '" + name + "' 是项目级技能。",
                    "无法删除"
            );
        }

        try {
            skillRegistry.uninstall(name);
            
            return ToolResult.ok(
                    String.format("技能 '%s' 已删除。", name),
                    "删除成功: " + name
            );
        } catch (Exception e) {
            log.error("删除技能失败: {}", name, e);
            return ToolResult.error(
                    "删除技能失败: " + e.getMessage(),
                    "删除失败"
            );
        }
    }

    /**
     * 验证必需参数
     */
    private String requireParam(String value, String paramName, String operation) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "对于 '" + operation + "' 操作，" + paramName + " 参数是必需的"
            );
        }
        return value.trim();
    }

    /**
     * SkillsTool 工具参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        
        @JsonPropertyDescription(
                "要执行的操作：" +
                "'list' - 列出所有已安装的技能; " +
                "'invoke' - 加载技能完整内容（需要 name 参数）; " +
                "'install' - 安装技能（需要 repo 参数，支持 GitHub 仓库如 'owner/repo' 或压缩包 URL）; " +
                "'create' - 创建新技能（需要 name、content 参数，可选 description）; " +
                "'edit' - 更新现有技能内容（需要 name、content 参数）; " +
                "'remove' - 删除指定技能（需要 name 参数）"
        )
        private String action;

        @JsonPropertyDescription(
                "技能名称。invoke、create、edit、remove 操作必需"
        )
        private String name;

        @JsonPropertyDescription(
                "安装来源。install 操作必需。支持以下格式：\n" +
                "- GitHub 仓库：'owner/repo' 或 'owner/repo/skill-name'\n" +
                "- 压缩包 URL：'https://example.com/skill.zip'"
        )
        private String repo;

        @JsonPropertyDescription(
                "技能内容。create 和 edit 操作必需。" +
                "应为 Markdown 格式的技能指令内容。"
        )
        private String content;

        @JsonPropertyDescription(
                "技能的简短描述。create 操作时可选，用于说明技能用途。"
        )
        private String description;
    }
}
