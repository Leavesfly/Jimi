package io.leavesfly.jimi.cli.command.custom;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * 自定义命令加载器
 * 从 YAML 配置文件加载自定义命令
 * 加载策略: ~/.jimi/commands/ -> project/.jimi/commands/
 */
@Slf4j
public class CustomCommandLoader {
    
    private final ObjectMapper yamlObjectMapper;
    
    public CustomCommandLoader(ObjectMapper yamlObjectMapper) {
        this.yamlObjectMapper = yamlObjectMapper;
    }
    
    /**
     * 从所有位置加载自定义命令
     */
    public List<CustomCommandSpec> loadAllCommands(Path projectDir) {
        List<CustomCommandSpec> allCommands = new ArrayList<>();
        
        List<CustomCommandSpec> userCommands = loadCommandsFromUserHome();
        allCommands.addAll(userCommands);
        log.debug("Loaded {} custom commands from user home", userCommands.size());
        
        if (projectDir != null) {
            List<CustomCommandSpec> projectCommands = loadCommandsFromProject(projectDir);
            allCommands.addAll(projectCommands);
            log.debug("Loaded {} custom commands from project", projectCommands.size());
        }
        
        log.info("Total {} custom commands loaded", allCommands.size());
        return allCommands;
    }
    
    private List<CustomCommandSpec> loadCommandsFromUserHome() {
        String userHome = System.getProperty("user.home");
        Path commandsDir = Paths.get(userHome, ".jimi", "commands");
        if (!Files.exists(commandsDir)) {
            return Collections.emptyList();
        }
        return loadCommandsFromDirectory(commandsDir, "user");
    }
    
    private List<CustomCommandSpec> loadCommandsFromProject(Path projectDir) {
        Path commandsDir = projectDir.resolve(".jimi").resolve("commands");
        if (!Files.exists(commandsDir)) {
            return Collections.emptyList();
        }
        return loadCommandsFromDirectory(commandsDir, "project");
    }
    
    private List<CustomCommandSpec> loadCommandsFromDirectory(Path directory, String source) {
        List<CustomCommandSpec> commands = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return commands;
        }
        try (Stream<Path> paths = Files.walk(directory, 1)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.getFileName().toString().endsWith(".yaml") || 
                             p.getFileName().toString().endsWith(".yml"))
                 .forEach(file -> {
                     try {
                         CustomCommandSpec spec = parseCommandFile(file);
                         if (spec != null) {
                             spec.setConfigFilePath(file.toString());
                             commands.add(spec);
                             log.debug("Loaded command '{}' from {} ({})", 
                                     spec.getName(), source, file.getFileName());
                         }
                     } catch (Exception e) {
                         log.error("Failed to load command from {}: {}", file, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.error("Failed to scan commands directory: {}", directory, e);
        }
        return commands;
    }
    
    public CustomCommandSpec parseCommandFile(Path file) {
        try {
            CustomCommandSpec spec = yamlObjectMapper.readValue(file.toFile(), CustomCommandSpec.class);
            spec.validate();
            return spec;
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse command file: " + file, e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid command configuration in " + file + ": " + e.getMessage(), e);
        }
    }
    
    public void ensureUserCommandsDirectory() {
        Path dir = Paths.get(System.getProperty("user.home"), ".jimi", "commands");
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                log.info("Created user commands directory: {}", dir);
            }
        } catch (IOException e) {
            log.error("Failed to create user commands directory: {}", dir, e);
        }
    }
}
