package io.leavesfly.jimi.adk.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Hook 加载器
 * 从文件系统扫描和加载 Hook 配置
 * 加载策略: classpath -> ~/.jimi/hooks/ -> project/.jimi/hooks/
 */
@Slf4j
public class HookLoader {
    
    private final ObjectMapper yamlObjectMapper;
    
    public HookLoader(ObjectMapper yamlObjectMapper) {
        this.yamlObjectMapper = yamlObjectMapper;
    }
    
    /**
     * 从所有位置加载 Hooks
     */
    public List<HookSpec> loadAllHooks(Path projectDir) {
        List<HookSpec> allHooks = new ArrayList<>();
        
        List<HookSpec> classpathHooks = loadHooksFromClasspath();
        allHooks.addAll(classpathHooks);
        log.debug("Loaded {} hooks from classpath", classpathHooks.size());
        
        List<HookSpec> userHooks = loadHooksFromUserHome();
        allHooks.addAll(userHooks);
        log.debug("Loaded {} hooks from user home", userHooks.size());
        
        if (projectDir != null) {
            List<HookSpec> projectHooks = loadHooksFromProject(projectDir);
            allHooks.addAll(projectHooks);
            log.debug("Loaded {} hooks from project directory", projectHooks.size());
        }
        
        log.info("Total {} hooks loaded", allHooks.size());
        return allHooks;
    }
    
    private List<HookSpec> loadHooksFromClasspath() {
        try {
            URL resource = getClass().getClassLoader().getResource("hooks");
            if (resource == null) {
                log.debug("No hooks directory found in classpath");
                return Collections.emptyList();
            }
            if (resource.getProtocol().equals("jar")) {
                log.debug("Running from JAR, skipping classpath hooks");
                return Collections.emptyList();
            }
            Path hooksDir = Paths.get(resource.toURI());
            return loadHooksFromDirectory(hooksDir, "classpath");
        } catch (Exception e) {
            log.warn("Failed to load hooks from classpath", e);
            return Collections.emptyList();
        }
    }
    
    private List<HookSpec> loadHooksFromUserHome() {
        String userHome = System.getProperty("user.home");
        Path hooksDir = Paths.get(userHome, ".jimi", "hooks");
        if (!Files.exists(hooksDir)) {
            log.debug("User hooks directory not found: {}", hooksDir);
            return Collections.emptyList();
        }
        return loadHooksFromDirectory(hooksDir, "user");
    }
    
    private List<HookSpec> loadHooksFromProject(Path projectDir) {
        Path hooksDir = projectDir.resolve(".jimi").resolve("hooks");
        if (!Files.exists(hooksDir)) {
            log.debug("Project hooks directory not found: {}", hooksDir);
            return Collections.emptyList();
        }
        return loadHooksFromDirectory(hooksDir, "project");
    }
    
    private List<HookSpec> loadHooksFromDirectory(Path directory, String source) {
        List<HookSpec> hooks = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return hooks;
        }
        try (Stream<Path> paths = Files.walk(directory, 1)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.getFileName().toString().endsWith(".yaml") || 
                             p.getFileName().toString().endsWith(".yml"))
                 .forEach(file -> {
                     try {
                         HookSpec spec = parseHookFile(file);
                         if (spec != null) {
                             spec.setConfigFilePath(file.toString());
                             hooks.add(spec);
                             log.debug("Loaded hook '{}' from {} ({})", 
                                     spec.getName(), source, file.getFileName());
                         }
                     } catch (Exception e) {
                         log.error("Failed to load hook from {}: {}", file, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.error("Failed to scan hooks directory: {}", directory, e);
        }
        return hooks;
    }
    
    /**
     * 解析单个 Hook 配置文件
     */
    public HookSpec parseHookFile(Path file) {
        try {
            log.debug("Parsing hook file: {}", file);
            HookSpec spec = yamlObjectMapper.readValue(file.toFile(), HookSpec.class);
            spec.validate();
            return spec;
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse hook file: " + file, e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid hook configuration in " + file + ": " + e.getMessage(), e);
        }
    }
    
    public Path getUserHooksDirectory() {
        return Paths.get(System.getProperty("user.home"), ".jimi", "hooks");
    }
    
    public Path getProjectHooksDirectory(Path projectDir) {
        return projectDir.resolve(".jimi").resolve("hooks");
    }
    
    /**
     * 确保用户 Hook 目录存在
     */
    public void ensureUserHooksDirectory() {
        Path dir = getUserHooksDirectory();
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                log.info("Created user hooks directory: {}", dir);
            }
        } catch (IOException e) {
            log.error("Failed to create user hooks directory: {}", dir, e);
        }
    }
}
