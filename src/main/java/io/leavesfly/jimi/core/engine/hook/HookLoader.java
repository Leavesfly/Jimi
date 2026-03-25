package io.leavesfly.jimi.core.engine.hook;

import io.leavesfly.jimi.common.YamlConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hook 加载器
 *
 * 委托 YamlConfigLoader 实现三层加载（classpath → ~/.jimi/ → project/.jimi/）
 */
@Slf4j
@Service
public class HookLoader {

    @Autowired
    private YamlConfigLoader yamlConfigLoader;

    /**
     * 从所有位置加载 Hooks
     */
    public List<HookSpec> loadAllHooks(Path projectDir) {
        List<HookSpec> loaded = yamlConfigLoader.loadAll("hooks", HookSpec.class, projectDir);

        List<HookSpec> validHooks = new ArrayList<>();
        for (HookSpec spec : loaded) {
            try {
                spec.validate();
                validHooks.add(spec);
            } catch (Exception e) {
                log.error("Invalid hook config: {}", e.getMessage());
            }
        }

        log.info("Total {} valid hooks loaded", validHooks.size());
        return validHooks;
    }

    /**
     * 解析单个 Hook 配置文件
     */
    public HookSpec parseHookFile(Path file) {
        HookSpec spec = yamlConfigLoader.parseYamlFile(file, HookSpec.class);
        spec.validate();
        return spec;
    }

    public Path getUserHooksDirectory() {
        return yamlConfigLoader.getUserDirectory("hooks");
    }

    public Path getProjectHooksDirectory(Path projectDir) {
        return yamlConfigLoader.getProjectDirectory("hooks", projectDir);
    }

    public void ensureUserHooksDirectory() {
        yamlConfigLoader.ensureUserDirectory("hooks");
    }
}
