package io.leavesfly.jimi.adk.skill;

/**
 * Skill 披露模式
 * <p>
 * 控制 Skill 内容如何被加载到 LLM 上下文中：
 * <ul>
 *   <li>{@link #EAGER} - 即时模式（默认）：匹配即全量注入 SKILL.md 正文</li>
 *   <li>{@link #PROGRESSIVE} - 逐步披露模式：L1 摘要 → L2 按需加载 → L3 按需读取</li>
 * </ul>
 */
public enum DisclosureMode {

    /**
     * 即时模式（现有行为）
     * <p>
     * SkillMatcher 匹配到 Skill 后，SkillInjector 立即将完整 SKILL.md 正文注入上下文。
     * 优点：简单直接，LLM 无需额外工具调用。
     * 缺点：Skill 数量多或内容长时，大量消耗上下文 token。
     */
    EAGER,

    /**
     * 逐步披露模式（对齐 Claude Code Skill 标准）
     * <p>
     * 三层渐进式加载：
     * <ol>
     *   <li>L1：系统提示中注入 {@code <available_skills>} 摘要（仅 name + description）</li>
     *   <li>L2：LLM 通过 ReadSkill 工具按需加载 SKILL.md 正文</li>
     *   <li>L3：LLM 通过 ReadSkillResource 工具按需读取辅助文件</li>
     * </ol>
     * 优点：极大节省上下文 token，LLM 自主决定加载哪些 Skill。
     * 缺点：依赖 LLM 正确判断并调用工具。
     */
    PROGRESSIVE
}
