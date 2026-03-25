package io.leavesfly.jimi.command.custom;

import io.leavesfly.jimi.common.YamlConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 自定义命令加载器
 *
 * 委托 YamlConfigLoader 实现三层加载（classpath → ~/.jimi/ → project/.jimi/）
 */
@Slf4j
@Service
public class CustomCommandLoader {

    @Autowired
    private YamlConfigLoader yamlConfigLoader;

    /**
     * 从所有位置加载自定义命令
     */
    public List<CustomCommandSpec> loadAllCommands(Path projectDir) {
        List<CustomCommandSpec> loaded = yamlConfigLoader.loadAll("commands", CustomCommandSpec.class, projectDir);

        List<CustomCommandSpec> validCommands = new ArrayList<>();
        for (CustomCommandSpec spec : loaded) {
            try {
                spec.validate();
                validCommands.add(spec);
            } catch (Exception e) {
                log.error("Invalid command config: {}", e.getMessage());
            }
        }

        log.info("Total {} valid commands loaded", validCommands.size());
        return validCommands;
    }

    /**
     * 解析单个命令配置文件
     */
    public CustomCommandSpec parseCommandFile(Path file) {
        CustomCommandSpec spec = yamlConfigLoader.parseYamlFile(file, CustomCommandSpec.class);
        spec.validate();
        return spec;
    }

    public Path getUserCommandsDirectory() {
        return yamlConfigLoader.getUserDirectory("commands");
    }

    public Path getProjectCommandsDirectory(Path projectDir) {
        return yamlConfigLoader.getProjectDirectory("commands", projectDir);
    }

    public void ensureUserCommandsDirectory() {
        yamlConfigLoader.ensureUserDirectory("commands");
    }
}
