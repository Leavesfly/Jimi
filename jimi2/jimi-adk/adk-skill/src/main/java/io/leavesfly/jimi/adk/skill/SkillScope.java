package io.leavesfly.jimi.adk.skill;

/**
 * Skill 作用域枚举
 */
public enum SkillScope {
    /** 全局作用域 - 从 ~/.jimi/skills/ 或 resources/skills/ 加载 */
    GLOBAL,
    /** 项目作用域 - 从项目根目录 .jimi/skills/ 加载 */
    PROJECT
}
