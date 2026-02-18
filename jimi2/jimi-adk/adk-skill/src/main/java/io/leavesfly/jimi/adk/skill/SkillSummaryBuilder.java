package io.leavesfly.jimi.adk.skill;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Skill 摘要生成器
 * <p>
 * 职责：将 SkillRegistry 中所有已注册的 Skills 生成 {@code <available_skills>} XML 摘要，
 * 用于注入系统提示。摘要仅包含 name + description，极低 token 成本。
 * </p>
 * <p>
 * 这是逐步披露（Progressive Disclosure）架构的第一层（L1），
 * 让 LLM 知道有哪些 Skill 可用，但不加载完整内容。
 * </p>
 */
@Slf4j
public class SkillSummaryBuilder {

    private final SkillRegistry skillRegistry;

    public SkillSummaryBuilder(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /**
     * 生成 {@code <available_skills>} 摘要文本
     * <p>
     * 仅包含每个 Skill 的 name、description 以及资源可用性标识，
     * 每个 Skill 约消耗 50 tokens。
     *
     * @return 摘要文本，无 Skill 时返回空字符串
     */
    public String buildSummary() {
        List<SkillSpec> allSkills = skillRegistry.getAllSkills();
        if (allSkills.isEmpty()) {
            log.debug("No skills registered, returning empty summary");
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<available_skills>\n");

        for (SkillSpec skill : allSkills) {
            sb.append("<skill>\n");
            sb.append("  <name>").append(escapeXml(skill.getName())).append("</name>\n");

            String description = skill.getDescription();
            if (description != null && !description.isEmpty()) {
                sb.append("  <description>").append(escapeXml(description)).append("</description>\n");
            }

            if (skill.getCategory() != null && !skill.getCategory().isEmpty()) {
                sb.append("  <category>").append(escapeXml(skill.getCategory())).append("</category>\n");
            }

            if (skill.getResourcesPath() != null) {
                sb.append("  <has_resources>true</has_resources>\n");
            }

            if (skill.getScriptsPath() != null) {
                sb.append("  <has_scripts>true</has_scripts>\n");
            }

            sb.append("</skill>\n");
        }

        sb.append("</available_skills>\n\n");
        sb.append("When a user's task matches one of the above skills, ");
        sb.append("use the `ReadSkill` tool to load its full instructions before proceeding. ");
        sb.append("If a skill has resources, you can use `ReadSkillResource` to read reference files.\n");

        log.info("Built skills summary for {} skills", allSkills.size());
        return sb.toString();
    }

    /**
     * 转义 XML 特殊字符
     */
    private String escapeXml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
}
