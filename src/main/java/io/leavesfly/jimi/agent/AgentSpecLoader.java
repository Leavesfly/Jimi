package io.leavesfly.jimi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.leavesfly.jimi.exception.AgentSpecException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
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
            Paths.get("defalut", "agent.yaml");

    @Autowired
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
     * 获取agents目录
     * 在资源目录中查找agents文件夹
     */
    private static Path getAgentsDir() {
        // 尝试从类路径获取agents目录
        try {
            var resource = AgentSpecLoader.class.getClassLoader().getResource("agents");
            if (resource != null) {
                return Paths.get(resource.toURI());
            }
        } catch (Exception e) {
            log.warn("无法从类路径加载agents目录，使用相对路径", e);
        }
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".jimi", "agents");

    }

    @PostConstruct
    void preloadAllSpecs() {
        try {
            agentsRootDir = getAgentsDir();
            if (Files.exists(agentsRootDir) && Files.isDirectory(agentsRootDir)) {
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
     * 加载Agent规范
     *
     * @param agentFile Agent配置文件路径
     * @return 已解析的Agent规范
     */
    public Mono<AgentSpec> loadAgentSpec(Path agentFile) {
        return Mono.fromCallable(() -> {
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
     * 内部加载方法，处理继承关系
     */
    private AgentSpec loadAgentSpecInternal(Path agentFile) {
        try {
            // 读取YAML文件
            Map<String, Object> data = yamlObjectMapper.readValue(
                    agentFile.toFile(),
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

        // 处理子Agent
        if (data.containsKey("subagents")) {
            Map<String, SubagentSpec> subagents = new HashMap<>();
            Map<String, Map<String, Object>> subagentsData =
                    (Map<String, Map<String, Object>>) data.get("subagents");
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
     * @return 默认 Agent 配置文件的绝对路径
     * @throws AgentSpecException 如果默认 Agent 不存在
     */
    public Path getDefaultAgentPath() {
        // 尝试多个可能的位置
        List<Path> candidates = List.of(
                agentsRootDir.resolve(DEFAULT_AGENT_RELATIVE_PATH)
        );

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
