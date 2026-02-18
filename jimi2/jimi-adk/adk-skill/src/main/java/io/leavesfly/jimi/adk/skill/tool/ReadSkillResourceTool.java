package io.leavesfly.jimi.adk.skill.tool;

import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolResult;
import io.leavesfly.jimi.adk.skill.SkillRegistry;
import io.leavesfly.jimi.adk.skill.SkillSpec;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ReadSkillResource 工具 — 逐步披露第三层（L3）
 * <p>
 * LLM 在通过 ReadSkill 工具加载 Skill 正文后，如果需要参考辅助文件
 * （如 checklist、模板、配置示例等），主动调用此工具按需读取。
 * </p>
 * <p>
 * 安全特性：
 * <ul>
 *   <li>路径穿越防护：只允许读取 Skill resources 目录内的文件</li>
 *   <li>文件不存在时列出可用文件，帮助 LLM 选择正确的文件名</li>
 * </ul>
 */
@Slf4j
public class ReadSkillResourceTool implements Tool<ReadSkillResourceTool.Params> {

    private final SkillRegistry skillRegistry;

    public ReadSkillResourceTool(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public String getName() {
        return "ReadSkillResource";
    }

    @Override
    public String getDescription() {
        return "Read a resource file from a skill's resources directory. " +
               "Use this when a loaded skill mentions available reference files " +
               "that you need to consult.";
    }

    @Override
    public Class<Params> getParamsType() {
        return Params.class;
    }

    @Override
    public Mono<ToolResult> execute(Params params) {
        if (params == null) {
            return Mono.just(ToolResult.error("Parameters are required."));
        }

        String skillName = params.getSkillName();
        String fileName = params.getFileName();

        if (skillName == null || skillName.isBlank()) {
            return Mono.just(ToolResult.error("Parameter 'skillName' is required."));
        }
        if (fileName == null || fileName.isBlank()) {
            return Mono.just(ToolResult.error("Parameter 'fileName' is required."));
        }

        skillName = skillName.trim();
        fileName = fileName.trim();

        log.info("ReadSkillResource tool invoked: skill='{}', file='{}'", skillName, fileName);

        // 查找 Skill
        Optional<SkillSpec> skillOpt = skillRegistry.findByName(skillName);
        if (skillOpt.isEmpty()) {
            return Mono.just(ToolResult.error("Skill not found: '" + skillName + "'."));
        }

        SkillSpec skill = skillOpt.get();
        if (skill.getResourcesPath() == null) {
            return Mono.just(ToolResult.error(
                "Skill '" + skillName + "' has no resources directory."));
        }

        Path resourcesDir = skill.getResourcesPath();
        Path resourceFile = resourcesDir.resolve(fileName).normalize();

        // 安全检查：防止路径穿越
        if (!resourceFile.startsWith(resourcesDir.normalize())) {
            log.warn("Path traversal attempt detected: skill='{}', file='{}'", skillName, fileName);
            return Mono.just(ToolResult.error(
                "Invalid file path: path traversal is not allowed."));
        }

        // 文件不存在时列出可用文件
        if (!Files.exists(resourceFile)) {
            String availableFiles = listAvailableFiles(resourcesDir);
            return Mono.just(ToolResult.error(
                "File not found: '" + fileName + "' in skill '" + skillName + "'. " +
                "Available files: " + availableFiles));
        }

        if (!Files.isRegularFile(resourceFile)) {
            return Mono.just(ToolResult.error(
                "'" + fileName + "' is not a regular file."));
        }

        // 读取文件内容
        try {
            String content = Files.readString(resourceFile);
            log.info("ReadSkillResource loaded file '{}' from skill '{}' ({} chars)",
                    fileName, skillName, content.length());
            return Mono.just(ToolResult.success(content));
        } catch (IOException e) {
            log.error("Failed to read resource file: skill='{}', file='{}'",
                    skillName, fileName, e);
            return Mono.just(ToolResult.error(
                "Failed to read file '" + fileName + "': " + e.getMessage()));
        }
    }

    /**
     * 列出 resources 目录下的可用文件
     */
    private String listAvailableFiles(Path resourcesDir) {
        try {
            return Files.list(resourcesDir)
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.joining(", "));
        } catch (IOException e) {
            log.debug("Failed to list resources directory", e);
            return "(unable to list files)";
        }
    }

    /**
     * ReadSkillResource 工具参数
     */
    @Data
    public static class Params {
        /** Skill 名称 */
        private String skillName;
        /** 要读取的资源文件名 */
        private String fileName;
    }
}
