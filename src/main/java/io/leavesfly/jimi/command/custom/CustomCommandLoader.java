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
 * 委托 YamlConfigLoader 实现三层加载（classpath → ~/.jimi/ → project/.jimi/）。
 * 加载后对每个命令配置进行验证，过滤无效配置。
 */
@Slf4j
@Service
public class CustomCommandLoader {

    @Autowired
    private YamlConfigLoader yamlConfigLoader;

    /**
     * 从所有位置加载自定义命令
     *
     * @param projectDir 项目目录（可选）
     * @return 验证通过的命令列表
     */
    public List<CustomCommandSpec> loadAllCommands(Path projectDir) {
        List<CustomCommandSpec> loaded = yamlConfigLoader.loadAll(
                "commands", CustomCommandSpec.class, projectDir);

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
     *
     * @param file YAML 配置文件路径
     * @return 解析并验证后的命令规范
     */
    public CustomCommandSpec parseCommandFile(Path file) {
        CustomCommandSpec spec = yamlConfigLoader.parseYamlFile(file, CustomCommandSpec.class);
        spec.validate();
        return spec;
    }

    /**
     * 获取用户级命令目录
     *
     * @return ~/.jimi/commands/ 路径
     */
    public Path getUserCommandsDirectory() {
        return yamlConfigLoader.getUserDirectory("commands");
    }

    /**
     * 获取项目级命令目录
     *
     * @param projectDir 项目根目录
     * @return project/.jimi/commands/ 路径
     */
    public Path getProjectCommandsDirectory(Path projectDir) {
        return yamlConfigLoader.getProjectDirectory("commands", projectDir);
    }

    /**
     * 确保用户级命令目录存在
     */
    public void ensureUserCommandsDirectory() {
        yamlConfigLoader.ensureUserDirectory("commands");
    }
}
