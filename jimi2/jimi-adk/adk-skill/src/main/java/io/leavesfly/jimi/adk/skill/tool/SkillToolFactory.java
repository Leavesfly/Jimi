package io.leavesfly.jimi.adk.skill.tool;

import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.skill.DisclosureMode;
import io.leavesfly.jimi.adk.skill.SkillConfig;
import io.leavesfly.jimi.adk.skill.SkillInjector;
import io.leavesfly.jimi.adk.skill.SkillRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * Skill 工具工厂
 * <p>
 * 在 PROGRESSIVE 模式下，创建 ReadSkill 和 ReadSkillResource 工具实例，
 * 供上层（如 ToolProvider SPI 实现或工具注册表）注册到 Agent 的工具集中。
 * </p>
 * <p>
 * 设计说明：
 * adk-skill 模块是纯 Java 模块，不依赖 Spring 或主项目的 ToolProvider SPI。
 * 因此通过工厂模式提供工具实例，由上层集成模块负责注册。
 * </p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * SkillToolFactory factory = new SkillToolFactory(registry, injector, config);
 * if (factory.shouldProvideTools()) {
 *     List<Tool<?>> tools = factory.createTools();
 *     toolRegistry.registerAll(tools);
 * }
 * }</pre>
 */
@Slf4j
public class SkillToolFactory {

    private final SkillRegistry skillRegistry;
    private final SkillInjector skillInjector;
    private final SkillConfig skillConfig;

    public SkillToolFactory(SkillRegistry skillRegistry,
                            SkillInjector skillInjector,
                            SkillConfig skillConfig) {
        this.skillRegistry = skillRegistry;
        this.skillInjector = skillInjector;
        this.skillConfig = skillConfig;
    }

    /**
     * 判断是否应该提供 Skill 工具
     * <p>
     * 仅在 PROGRESSIVE 模式下提供 ReadSkill 和 ReadSkillResource 工具。
     *
     * @return 是否应该提供工具
     */
    public boolean shouldProvideTools() {
        if (skillConfig == null) {
            return false;
        }
        boolean shouldProvide = skillConfig.getDisclosureMode() == DisclosureMode.PROGRESSIVE;
        if (shouldProvide) {
            log.info("PROGRESSIVE disclosure mode enabled, Skill tools will be provided");
        }
        return shouldProvide;
    }

    /**
     * 创建 Skill 相关工具实例
     * <p>
     * 返回 ReadSkill 和 ReadSkillResource 两个工具。
     * 仅在 {@link #shouldProvideTools()} 返回 true 时调用。
     *
     * @return 工具实例列表
     */
    public List<Tool<?>> createTools() {
        if (!shouldProvideTools()) {
            log.debug("Skill tools not needed in current disclosure mode");
            return Collections.emptyList();
        }

        ReadSkillTool readSkillTool = new ReadSkillTool(skillRegistry, skillInjector);
        ReadSkillResourceTool readSkillResourceTool = new ReadSkillResourceTool(skillRegistry);

        log.info("Created Skill tools: [ReadSkill, ReadSkillResource]");
        return List.of(readSkillTool, readSkillResourceTool);
    }
}
