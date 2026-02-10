package io.leavesfly.jimi.adk.skill;

import io.leavesfly.jimi.adk.api.skill.SkillDescriptor;
import io.leavesfly.jimi.adk.api.skill.SkillService;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SkillService 默认实现
 * <p>
 * 组合 SkillRegistry、SkillMatcher、SkillInjector 提供统一的 Skill 服务能力。
 * 实现 adk-api 中定义的 {@link SkillService} 接口，使得 adk-core 和上层应用
 * 可以通过接口依赖 Skill 能力，而无需直接依赖 adk-skill 模块的内部实现。
 * </p>
 */
@Slf4j
public class DefaultSkillService implements SkillService {

    private final SkillRegistry skillRegistry;
    private final SkillMatcher skillMatcher;
    private final SkillInjector skillInjector;
    private volatile boolean initialized = false;

    public DefaultSkillService(SkillRegistry skillRegistry,
                               SkillMatcher skillMatcher,
                               SkillInjector skillInjector) {
        this.skillRegistry = skillRegistry;
        this.skillMatcher = skillMatcher;
        this.skillInjector = skillInjector;
    }

    /**
     * 初始化 Skill 服务（加载全局 Skills）
     */
    public void init() {
        skillRegistry.init();
        initialized = true;
        log.info("DefaultSkillService initialized");
    }

    @Override
    public List<SkillDescriptor> matchSkills(String inputText) {
        if (!initialized) {
            log.warn("SkillService not initialized, returning empty list");
            return Collections.emptyList();
        }

        List<SkillSpec> matched = skillMatcher.matchFromInput(inputText);
        return matched.stream()
                .map(this::toDescriptor)
                .collect(Collectors.toList());
    }

    @Override
    public String matchAndFormat(String inputText, Path workDir) {
        if (!initialized) {
            log.warn("SkillService not initialized, skipping skill injection");
            return null;
        }

        List<SkillSpec> matched = skillMatcher.matchFromInput(inputText);
        if (matched.isEmpty()) {
            return null;
        }

        return skillInjector.formatSkillsForInjection(matched, workDir);
    }

    @Override
    public void loadProjectSkills(Path projectSkillsDir) {
        skillRegistry.loadProjectSkills(projectSkillsDir);
    }

    @Override
    public List<String> getActiveSkillNames() {
        return skillInjector.getActiveSkills().stream()
                .map(SkillSpec::getName)
                .collect(Collectors.toList());
    }

    @Override
    public void clearActiveSkills() {
        skillInjector.clearActiveSkills();
    }

    @Override
    public List<SkillDescriptor> getAllSkills() {
        return skillRegistry.getAllSkills().stream()
                .map(this::toDescriptor)
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getStatistics() {
        return skillRegistry.getStatistics();
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 获取内部的 SkillRegistry（供需要直接操作的场景使用）
     */
    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }

    /**
     * 获取内部的 SkillMatcher（供需要直接操作的场景使用）
     */
    public SkillMatcher getSkillMatcher() {
        return skillMatcher;
    }

    /**
     * 获取内部的 SkillInjector（供需要直接操作的场景使用）
     */
    public SkillInjector getSkillInjector() {
        return skillInjector;
    }

    private SkillDescriptor toDescriptor(SkillSpec spec) {
        return SkillDescriptor.builder()
                .name(spec.getName())
                .description(spec.getDescription())
                .version(spec.getVersion())
                .category(spec.getCategory())
                .triggers(spec.getTriggers())
                .scope(spec.getScope() != null ? spec.getScope().name() : null)
                .build();
    }
}
