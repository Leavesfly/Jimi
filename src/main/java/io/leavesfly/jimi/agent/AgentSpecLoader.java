package io.leavesfly.jimi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.leavesfly.jimi.exception.AgentSpecException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent规范加载器
 * 负责从YAML文件加载Agent配置，处理继承关系
 * <p>
 * 外部模块应通过 {@link AgentRegistry} 来访问 Agent 加载功能。
 */
@Slf4j
@Service
class AgentSpecLoader {


    /**
     * 默认 Agent 文件路径（相对于 agents 目录）
     */
    private static final Path DEFAULT_AGENT_RELATIVE_PATH =
            Paths.get("default", "agent.yaml");

    @Autowired
    @Qualifier("yamlObjectMapper")
    private ObjectMapper yamlObjectMapper;


    /**
     * agents 根目录
     */
    private Path agentsRootDir;


    /**
     * 预加载规范缓存（绝对路径 -> 规范）
     */
    private final Map<Path, AgentSpec> specCache = new ConcurrentHashMap<>();

    /**
     * 判断是否在JAR包内运行
     */
    private static boolean isRunningFromJar() {
        try {
            URL resource = AgentSpecLoader.class.getClassLoader().getResource("agents");
            return resource != null && resource.getProtocol().equals("jar");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取agents目录
     * 在资源目录中查找agents文件夹
     */
    private static Path getAgentsDir() {
        // JAR包内运行时,返回null,使用资源流方式加载
        if (isRunningFromJar()) {
            log.debug("Running from JAR, agents will be loaded from classpath resources");
            return null;
        }
        
        // 尝试从类路径获取agents目录
        try {
            URL resource = AgentSpecLoader.class.getClassLoader().getResource("agents");
            if (resource != null) {
                return Paths.get(resource.toURI());
            }
        } catch (Exception e) {
            log.warn("无法从类路径加载agents目录，使用用户目录", e);
        }
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".jimi", "agents");
    }

    @PostConstruct
    void preloadAllSpecs() {
        try {
            agentsRootDir = getAgentsDir();
            
            // JAR包内运行时,从类路径资源加载
            if (agentsRootDir == null) {
                preloadFromClasspath();
            } else if (Files.exists(agentsRootDir) && Files.isDirectory(agentsRootDir)) {
                // 文件系统模式
                Files.list(agentsRootDir)
                        .filter(Files::isDirectory)
                        .forEach(sub -> {
                            Path yaml = sub.resolve("agent.yaml");
                            if (Files.exists(yaml)) {
                                try {
                                    loadAgentSpec(yaml).block();
                                    log.debug("Preloaded agent spec: {}", yaml);
                                } catch (Exception e) {
                                    log.warn("Failed to preload agent spec: {}", yaml, e);
                                }
                            }
                        });
            }
            log.info("AgentSpecLoader preload completed. Cached specs: {}", specCache.size());
        } catch (Exception e) {
            log.warn("Preloading agent specs failed", e);
        }
    }

    /**
     * 从类路径资源预加载(JAR包模式)
     */
    private void preloadFromClasspath() {
        String[] agentDirs = {"default", "code", "debug", "test", "design", "doc", "review", "build", "deploy", "research"};
        for (String dir : agentDirs) {
            String resourcePath = "agents/" + dir + "/agent.yaml";
            try {
                URL resource = getClass().getClassLoader().getResource(resourcePath);
                if (resource != null) {
                    loadAgentSpecFromResource(resourcePath, dir).block();
                    log.debug("Preloaded agent spec from classpath: {}", resourcePath);
                }
            } catch (Exception e) {
                log.warn("Failed to preload agent spec from classpath: {}", resourcePath, e);
            }
        }
    }

    /**
     * 从类路径资源加载Agent规范(JAR包模式)
     */
    private Mono<AgentSpec> loadAgentSpecFromResource(String resourcePath, String agentName) {
        return Mono.fromCallable(() -> {
            // 使用资源路径作为缓存键
            Path cacheKey = Paths.get(resourcePath);
            AgentSpec cached = specCache.get(cacheKey);
            if (cached != null) {
                log.debug("Agent spec cache hit: {}", resourcePath);
                return cached;
            }

            log.info("正在加载Agent规范: {}", resourcePath);

            AgentSpec agentSpec = loadAgentSpecFromResourceInternal(resourcePath, agentName);

            // 验证必填字段
            if (agentSpec.getName() == null || agentSpec.getName().isEmpty()) {
                throw new AgentSpecException("Agent名称不能为空");
            }
            if (agentSpec.getSystemPromptPath() == null) {
                throw new AgentSpecException("系统提示词路径不能为空");
            }
            if (agentSpec.getTools() == null) {
                throw new AgentSpecException("工具列表不能为空");
            }

            specCache.put(cacheKey, agentSpec);
            log.debug("Agent spec cached: {}", resourcePath);
            return agentSpec;
        });
    }

    /**
     * 加载Agent规范
     *
     * @param agentFile Agent配置文件路径
     * @return 已解析的Agent规范
     */
    public Mono<AgentSpec> loadAgentSpec(Path agentFile) {
        return Mono.fromCallable(() -> {
            // 检查是否是classpath资源
            String pathStr = agentFile.toString();
            if (pathStr.startsWith("classpath:")) {
                // 从classpath加载
                String resourcePath = pathStr.substring("classpath:".length());
                String agentName = resourcePath.contains("/") ? 
                    resourcePath.substring(resourcePath.indexOf("agents/") + 7, resourcePath.lastIndexOf("/")) : 
                    "default";
                return loadAgentSpecFromResource(resourcePath, agentName).block();
            }
            
            // 文件系统模式
            Path absolute = agentFile.toAbsolutePath().normalize();
            AgentSpec cached = specCache.get(absolute);
            if (cached != null) {
                log.debug("Agent spec cache hit: {}", absolute);
                return cached;
            }

            log.info("正在加载Agent规范: {}", absolute);

            if (!Files.exists(absolute)) {
                throw new AgentSpecException("Agent文件不存在: " + absolute);
            }

            AgentSpec agentSpec = loadAgentSpecInternal(absolute);

            // 验证必填字段
            if (agentSpec.getName() == null || agentSpec.getName().isEmpty()) {
                throw new AgentSpecException("Agent名称不能为空");
            }
            if (agentSpec.getSystemPromptPath() == null) {
                throw new AgentSpecException("系统提示词路径不能为空");
            }
            if (agentSpec.getTools() == null) {
                throw new AgentSpecException("工具列表不能为空");
            }

            specCache.put(absolute, agentSpec);
            log.debug("Agent spec cached: {}", absolute);
            return agentSpec;
        });
    }

    /**
     * 从资源流加载Agent规范(JAR包模式)
     */
    private AgentSpec loadAgentSpecFromResourceInternal(String resourcePath, String agentName) {
        try {
            // 从类路径读取YAML
            Map<String, Object> data = yamlObjectMapper.readValue(
                    getClass().getClassLoader().getResourceAsStream(resourcePath),
                    Map.class
            );

            // 解析agent配置
            AgentSpec agentSpec = parseAgentSpecFromResource(data, agentName);
            return agentSpec;

        } catch (IOException e) {
            throw new AgentSpecException("加载Agent规范失败: " + e.getMessage(), e);
        }
    }

    /**
     * 内部加载方法，处理继承关系
     */
    private AgentSpec loadAgentSpecInternal(Path agentFile) {
        try {
            // 读取YAML文件
            Map<String, Object> data = yamlObjectMapper.readValue(
                    Files.newInputStream(agentFile),
                    Map.class
            );

            // 直接解析agent配置
            AgentSpec agentSpec = parseAgentSpec(data, agentFile);

            return agentSpec;

        } catch (IOException e) {
            throw new AgentSpecException("加载Agent规范失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从资源解析AgentSpec对象(JAR包模式)
     */
    private AgentSpec parseAgentSpecFromResource(Map<String, Object> data, String agentName) {
        AgentSpec.AgentSpecBuilder builder = AgentSpec.builder();

        // 处理名称
        if (data.containsKey("name")) {
            builder.name((String) data.get("name"));
        }

        // 处理系统提示词路径 - 使用虚拟路径标识资源
        if (data.containsKey("system_prompt")) {
            String promptFile = (String) data.get("system_prompt");
            Path virtualPath = Paths.get("classpath:agents/" + agentName + "/" + promptFile);
            builder.systemPromptPath(virtualPath);
        } else if (data.containsKey("system_prompt_path")) {
            String promptFile = (String) data.get("system_prompt_path");
            Path virtualPath = Paths.get("classpath:agents/" + agentName + "/" + promptFile);
            builder.systemPromptPath(virtualPath);
        }

        // 处理系统提示词参数
        if (data.containsKey("system_prompt_args")) {
            builder.systemPromptArgs((Map<String, String>) data.get("system_prompt_args"));
        }

        // 处理工具列表
        if (data.containsKey("tools")) {
            builder.tools((List<String>) data.get("tools"));
        }

        // 处理排除工具列表
        if (data.containsKey("exclude_tools")) {
            builder.excludeTools((List<String>) data.get("exclude_tools"));
        }

        // 处理模型名称
        if (data.containsKey("model")) {
            builder.model((String) data.get("model"));
        }

        // JAR包模式下也支持subagents
        if (data.containsKey("subagents")) {
            Map<String, SubagentSpec> subagents = new HashMap<>();
            Map<String, Map<String, Object>> subagentsData = (Map<String, Map<String, Object>>) data.get("subagents");
            for (Map.Entry<String, Map<String, Object>> entry : subagentsData.entrySet()) {
                String subagentPath = (String) entry.getValue().get("path");
                // 构造 classpath 虚拟路径
                Path resolvedPath = Paths.get("classpath:agents/" + subagentPath);

                SubagentSpec subagent = SubagentSpec.builder()
                        .path(resolvedPath)
                        .description((String) entry.getValue().get("description"))
                        .build();
                subagents.put(entry.getKey(), subagent);
                log.debug("Loaded subagent '{}' with path: {}", entry.getKey(), resolvedPath);
            }
            builder.subagents(subagents);
            log.debug("Loaded {} subagents for agent: {}", subagents.size(), agentName);
        }

        return builder.build();
    }

    /**
     * 解析AgentSpec对象
     */
    private AgentSpec parseAgentSpec(Map<String, Object> data, Path agentFile) {
        AgentSpec.AgentSpecBuilder builder = AgentSpec.builder();


        // 处理名称
        if (data.containsKey("name")) {
            builder.name((String) data.get("name"));
        }

        // 处理系统提示词路径（支持 system_prompt 和 system_prompt_path）
        Path systemPromptPath = null;
        if (data.containsKey("system_prompt")) {
            systemPromptPath = agentFile.getParent().resolve((String) data.get("system_prompt"));
        } else if (data.containsKey("system_prompt_path")) {
            systemPromptPath = agentFile.getParent().resolve((String) data.get("system_prompt_path"));
        }
        if (systemPromptPath != null) {
            builder.systemPromptPath(systemPromptPath);
        }

        // 处理系统提示词参数
        if (data.containsKey("system_prompt_args")) {
            builder.systemPromptArgs((Map<String, String>) data.get("system_prompt_args"));
        }

        // 处理工具列表
        if (data.containsKey("tools")) {
            builder.tools((List<String>) data.get("tools"));
        }

        // 处理排除工具列表
        if (data.containsKey("exclude_tools")) {
            builder.excludeTools((List<String>) data.get("exclude_tools"));
        }

        // 处理模型名称
        if (data.containsKey("model")) {
            builder.model((String) data.get("model"));
        }

        // 处理子Agent
        if (data.containsKey("subagents")) {
            Map<String, SubagentSpec> subagents = new HashMap<>();
            Map<String, Map<String, Object>> subagentsData = (Map<String, Map<String, Object>>) data.get("subagents");
            for (Map.Entry<String, Map<String, Object>> entry : subagentsData.entrySet()) {
                String subagentPath = (String) entry.getValue().get("path");
                // 将相对路径转换为绝对路径
                Path resolvedPath = agentsRootDir.resolve(subagentPath);

                SubagentSpec subagent = SubagentSpec.builder()
                        .path(resolvedPath)
                        .description((String) entry.getValue().get("description"))
                        .build();
                subagents.put(entry.getKey(), subagent);
            }
            builder.subagents(subagents);
        }

        return builder.build();
    }


    /**
     * 获取默认 Agent 配置文件路径
     *
     * @return 默认 Agent 配置文件的路径(文件系统模式)或资源路径(类路径模式)
     * @throws AgentSpecException 如果默认 Agent 不存在
     */
    public Path getDefaultAgentPath() {
        // JAR包模式 - 返回虚拟路径标识
        if (agentsRootDir == null) {
            String resourcePath = "agents/default/agent.yaml";
            URL resource = getClass().getClassLoader().getResource(resourcePath);
            if (resource != null) {
                log.debug("Found default agent in classpath: {}", resourcePath);
                return Paths.get("classpath:" + resourcePath);
            }
            throw new AgentSpecException("Default agent not found in classpath: " + resourcePath);
        }
        
        // 文件系统模式
        List<Path> candidates = List.of(agentsRootDir.resolve(DEFAULT_AGENT_RELATIVE_PATH));

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                log.debug("Found default agent at: {}", candidate);
                return candidate.toAbsolutePath();
            }
        }

        throw new AgentSpecException("Default agent not found in any expected location");
    }


    public Map<Path, AgentSpec> getSpecCache() {
        return Collections.unmodifiableMap(specCache);
    }

}
