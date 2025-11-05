package io.leavesfly.jimi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.leavesfly.jimi.exception.AgentSpecException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * 默认Agent文件路径
     */
    private static final Path DEFAULT_AGENT_FILE = getAgentsDir()
            .resolve("default")
            .resolve("agent.yaml");


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

        // 回退到相对路径
        return Paths.get("src/main/resources/agents");
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

            // 转换为ResolvedAgentSpec并缓存
            AgentSpec resolved = AgentSpec.builder()
                    .name(agentSpec.getName())
                    .systemPromptPath(agentSpec.getSystemPromptPath())
                    .systemPromptArgs(agentSpec.getSystemPromptArgs())
                    .tools(agentSpec.getTools())
                    .excludeTools(agentSpec.getExcludeTools() != null
                            ? agentSpec.getExcludeTools()
                            : new ArrayList<>())
                    .subagents(agentSpec.getSubagents() != null
                            ? agentSpec.getSubagents()
                            : new HashMap<>())
                    .build();
            specCache.put(absolute, resolved);
            log.debug("Agent spec cached: {}", absolute);
            return resolved;
        });
    }

    /**
     * 内部加载方法，处理继承关系
     */
    private static AgentSpec loadAgentSpecInternal(Path agentFile) {
        try {
            // 读取YAML文件
            Map<String, Object> data = YAML_MAPPER.readValue(
                    agentFile.toFile(),
                    Map.class
            );

            // 检查版本
            int version = (int) data.getOrDefault("version", 1);
            if (version != 1) {
                throw new AgentSpecException("不支持的Agent规范版本: " + version);
            }

            // 解析agent配置
            Map<String, Object> agentData = (Map<String, Object>) data.get("agent");
            if (agentData == null) {
                throw new AgentSpecException("缺少'agent'配置节");
            }

            AgentSpec agentSpec = parseAgentSpec(agentData);

            // 处理相对路径
            if (agentSpec.getSystemPromptPath() != null) {
                agentSpec.setSystemPromptPath(
                        agentFile.getParent().resolve(agentSpec.getSystemPromptPath())
                );
            }

            // 处理subagents路径
            if (agentSpec.getSubagents() != null) {
                for (SubagentSpec subagent : agentSpec.getSubagents().values()) {
                    subagent.setPath(agentFile.getParent().resolve(subagent.getPath()));
                }
            }

            // 处理继承
            if (agentSpec.getExtend() != null) {
                Path baseAgentFile;
                if ("default".equals(agentSpec.getExtend())) {
                    baseAgentFile = DEFAULT_AGENT_FILE;
                } else {
                    baseAgentFile = agentFile.getParent().resolve(agentSpec.getExtend());
                }

                log.debug("继承Agent配置: {}", baseAgentFile);
                AgentSpec baseSpec = loadAgentSpecInternal(baseAgentFile);

                // 合并配置
                agentSpec = mergeAgentSpecs(baseSpec, agentSpec);
            }

            return agentSpec;

        } catch (IOException e) {
            throw new AgentSpecException("加载Agent规范失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析AgentSpec对象
     */
    private static AgentSpec parseAgentSpec(Map<String, Object> data) {
        AgentSpec.AgentSpecBuilder builder = AgentSpec.builder();

        if (data.containsKey("extend")) {
            builder.extend((String) data.get("extend"));
        }
        if (data.containsKey("name")) {
            builder.name((String) data.get("name"));
        }
        if (data.containsKey("system_prompt_path")) {
            builder.systemPromptPath(Paths.get((String) data.get("system_prompt_path")));
        }
        if (data.containsKey("system_prompt_args")) {
            builder.systemPromptArgs((Map<String, String>) data.get("system_prompt_args"));
        }
        if (data.containsKey("tools")) {
            builder.tools((List<String>) data.get("tools"));
        }
        if (data.containsKey("exclude_tools")) {
            builder.excludeTools((List<String>) data.get("exclude_tools"));
        }
        if (data.containsKey("subagents")) {
            Map<String, SubagentSpec> subagents = new HashMap<>();
            Map<String, Map<String, Object>> subagentsData =
                    (Map<String, Map<String, Object>>) data.get("subagents");
            for (Map.Entry<String, Map<String, Object>> entry : subagentsData.entrySet()) {
                SubagentSpec subagent = SubagentSpec.builder()
                        .path(Paths.get((String) entry.getValue().get("path")))
                        .description((String) entry.getValue().get("description"))
                        .build();
                subagents.put(entry.getKey(), subagent);
            }
            builder.subagents(subagents);
        }

        return builder.build();
    }

    /**
     * 合并两个AgentSpec（子类覆盖基类）
     */
    private static AgentSpec mergeAgentSpecs(AgentSpec base, AgentSpec override) {
        AgentSpec.AgentSpecBuilder builder = AgentSpec.builder();

        // name: 优先使用override
        builder.name(override.getName() != null ? override.getName() : base.getName());

        // system_prompt_path: 优先使用override
        builder.systemPromptPath(override.getSystemPromptPath() != null
                ? override.getSystemPromptPath()
                : base.getSystemPromptPath());

        // system_prompt_args: 合并（override覆盖base）
        Map<String, String> mergedArgs = new HashMap<>(base.getSystemPromptArgs());
        if (override.getSystemPromptArgs() != null) {
            mergedArgs.putAll(override.getSystemPromptArgs());
        }
        builder.systemPromptArgs(mergedArgs);

        // tools: 优先使用override
        builder.tools(override.getTools() != null ? override.getTools() : base.getTools());

        // exclude_tools: 优先使用override
        builder.excludeTools(override.getExcludeTools() != null
                ? override.getExcludeTools()
                : base.getExcludeTools());

        // subagents: 优先使用override
        builder.subagents(override.getSubagents() != null
                ? override.getSubagents()
                : base.getSubagents());

        // extend应该被清空（已经展开）
        builder.extend(null);

        return builder.build();
    }


    /**
     * 默认 Agent 文件路径（相对于 agents 目录）
     */
    private static final Path DEFAULT_AGENT_RELATIVE_PATH =
            Paths.get("default", "agent.yaml");

    /**
     * 获取默认 Agent 配置文件路径
     *
     * @return 默认 Agent 配置文件的绝对路径
     * @throws AgentSpecException 如果默认 Agent 不存在
     */
    public Path getDefaultAgentPath() {
        // 尝试多个可能的位置
        List<Path> candidates = List.of(
                agentsRootDir.resolve(DEFAULT_AGENT_RELATIVE_PATH),
                Paths.get("src/main/resources/agents/default/agent.yaml"),
                Paths.get("agents/default/agent.yaml")
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
        return specCache;
    }

}
