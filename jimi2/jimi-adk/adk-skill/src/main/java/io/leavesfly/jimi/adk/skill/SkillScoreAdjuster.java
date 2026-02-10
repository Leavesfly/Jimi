package io.leavesfly.jimi.adk.skill;

import java.util.Set;

/**
 * Skill 匹配得分调整器扩展点
 *
 * <p>设计目标：
 * <ul>
 *     <li>在现有关键词匹配得分基础上，按需叠加语义匹配、上下文感知等高级策略；</li>
 *     <li>通过构造函数传入 List，支持在外部模块中按需新增实现，而无需修改核心匹配逻辑；</li>
 *     <li>保持向后兼容：如果没有任何实现被注册，行为与原来完全一致。</li>
 * </ul>
 */
public interface SkillScoreAdjuster {

    /**
     * 调整指定 Skill 的匹配得分
     *
     * @param skill     当前候选 Skill
     * @param baseScore 基础得分（基于触发词/名称/描述等规则计算）
     * @param keywords  从用户输入或上下文中提取的关键词集合
     * @param fullText  完整原始文本
     * @return 调整后的得分，建议范围 0-100，实际会在调用方统一裁剪
     */
    int adjustScore(SkillSpec skill,
                    int baseScore,
                    Set<String> keywords,
                    String fullText);
}
