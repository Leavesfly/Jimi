package io.leavesfly.jimi.plugin.dispatcher;

import io.leavesfly.jimi.plugin.spec.PluginScope;
import io.leavesfly.jimi.plugin.spec.PluginSpec;
import io.leavesfly.jimi.skill.SkillLoader;
import io.leavesfly.jimi.skill.SkillRegistry;
import io.leavesfly.jimi.skill.SkillSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Skills 扩展点适配器
 *
 * <p>把插件目录下 {@code skills/} 子目录中的 {@code SKILL.md} 文件桥接到
 * {@link SkillRegistry}。
 *
 * <p>加载流程：
 * <ol>
 *   <li>确认 {@code &lt;plugin&gt;/skills/} 子目录存在</li>
 *   <li>复用 {@link SkillLoader#loadSkillsFromDirectory(Path, SkillSpec.SkillScope)} 扫描</li>
 *   <li>按 {@link PluginSpec#getProvides()} 白名单过滤</li>
 *   <li>逐个调 {@link SkillRegistry#register(SkillSpec)} 注册</li>
 * </ol>
 *
 * <p>关于 Skill 的 {@code SkillScope}：
 * <ul>
 *   <li>插件 {@link PluginScope#PROJECT} → Skill 作用域 {@code PROJECT}</li>
 *   <li>其他情况（CLASSPATH / USER）→ Skill 作用域 {@code GLOBAL}</li>
 * </ul>
 */
@Slf4j
@Component
public class SkillModuleAdapter implements PluginModuleAdapter {

    /** 子目录名约定 */
    public static final String SUBDIR = "skills";

    @Autowired
    private SkillLoader skillLoader;

    @Autowired
    private SkillRegistry skillRegistry;

    @Override
    public String getModuleName() {
        return SUBDIR;
    }

    @Override
    public boolean supports(Path pluginDir) {
        Path subDir = pluginDir.resolve(SUBDIR);
        return Files.isDirectory(subDir);
    }

    @Override
    public ModuleLoadResult load(Path pluginDir, PluginSpec spec) {
        Path skillsDir = pluginDir.resolve(SUBDIR);

        SkillSpec.SkillScope skillScope = mapScope(spec.getScope());

        try {
            List<SkillSpec> loaded = skillLoader.loadSkillsFromDirectory(skillsDir, skillScope);
            List<String> registered = new ArrayList<>();

            for (SkillSpec skill : loaded) {
                String skillName = skill.getName();
                if (skillName == null || skillName.isBlank()) {
                    log.warn("Plugin '{}' skipping skill without name", spec.getName());
                    continue;
                }
                if (!spec.getProvides().allowsSkill(skillName)) {
                    log.debug("Plugin '{}' skill '{}' filtered by provides whitelist",
                            spec.getName(), skillName);
                    continue;
                }
                skillRegistry.register(skill);
                registered.add(skillName);
            }

            return ModuleLoadResult.success(registered);
        } catch (Exception e) {
            return ModuleLoadResult.failed(e);
        }
    }

    @Override
    public void unload(PluginSpec spec, ModuleLoadResult previousResult) {
        if (previousResult == null || previousResult.getLoadedItems().isEmpty()) {
            return;
        }
        int removed = 0;
        for (String skillName : previousResult.getLoadedItems()) {
            try {
                if (skillRegistry.unregister(skillName)) {
                    removed++;
                }
            } catch (RuntimeException e) {
                log.warn("Plugin '{}' failed to unregister skill '{}': {}",
                        spec.getName(), skillName, e.getMessage());
            }
        }
        log.info("Plugin '{}' unloaded {}/{} skill(s)",
                spec.getName(), removed, previousResult.getLoadedItems().size());
    }

    /**
     * 映射插件作用域到 Skill 作用域。
     *
     * @param pluginScope 插件作用域
     * @return Skill 作用域
     */
    private SkillSpec.SkillScope mapScope(PluginScope pluginScope) {
        if (pluginScope == PluginScope.PROJECT) {
            return SkillSpec.SkillScope.PROJECT;
        }
        return SkillSpec.SkillScope.GLOBAL;
    }
}
