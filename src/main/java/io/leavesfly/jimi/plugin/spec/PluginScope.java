package io.leavesfly.jimi.plugin.spec;

/**
 * 插件作用域枚举
 *
 * <p>定义插件的来源与生效范围，决定同名扩展点冲突时的覆盖顺序：
 * <ul>
 *   <li>{@link #CLASSPATH}：JAR 内置插件，优先级最低</li>
 *   <li>{@link #USER}：用户级插件（{@code ~/.jimi/plugins/}），跨项目生效</li>
 *   <li>{@link #PROJECT}：项目级插件（{@code <project>/.jimi/plugins/}），优先级最高</li>
 * </ul>
 *
 * <p>加载顺序与优先级严格对齐 Jimi 现有的三层加载约定：
 * {@code classpath < user < project}。同名扩展点后加载的覆盖先加载的。
 *
 * @see io.leavesfly.jimi.skill.SkillSpec.SkillScope
 */
public enum PluginScope {

    /**
     * 类路径作用域
     *
     * <p>从 JAR 内部的 {@code classpath:/plugins/} 目录加载，
     * 通常由 Jimi 官方发布的内置插件使用。
     */
    CLASSPATH,

    /**
     * 用户作用域
     *
     * <p>从 {@code ~/.jimi/plugins/} 加载，跨项目共享，
     * 用户通过 {@code /plugin install} 命令安装的插件默认落到此处。
     */
    USER,

    /**
     * 项目作用域
     *
     * <p>从 {@code <project>/.jimi/plugins/} 加载，
     * 仅对当前项目生效，适合随 git 提交共享给团队。
     */
    PROJECT
}
