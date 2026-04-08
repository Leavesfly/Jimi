package io.leavesfly.jimi.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.team.TeamSpec;
import io.leavesfly.jimi.team.TeamStrategy;
import io.leavesfly.jimi.team.TeamTaskSpec;
import io.leavesfly.jimi.team.TeammateSpec;
import io.leavesfly.jimi.exception.AgentSpecException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.io.InputStream;
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
 * 负责从YAML文件加载Agent配置，支持文件系统和classpath两种模式。
 * <p>
 * 外部模块应通过 {@link AgentRegistry} 来访问 Agent 加载功能。
 */
@Slf4j
@Service
class AgentSpecLoader {

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String AGENTS_RESOURCE_PREFIX = "agents/";
    private static final Path DEFAULT_AGENT_RELATIVE_PATH = Paths.get("default", "agent.yaml");

    @Autowired
    @Qualifier("yamlObjectMapper")
    private ObjectMapper yamlObjectMapper;

    private Path agentsRootDir;

    private final Map<Path, AgentSpec> specCache = new ConcurrentHashMap<>();

    @PostConstruct
    void preloadAllSpecs() {
        try {
            agentsRootDir = resolveAgentsDir();

            if (agentsRootDir == null) {
                preloadFromClasspath();
            } else {
                preloadFromFileSystem();
            }
            log.info("AgentSpecLoader preload completed. Cached specs: {}", specCache.size());
        } catch (Exception e) {
            log.warn("Preloading agent specs failed", e);
        }
    }

    /**
     * 加载Agent规范（统一入口，支持 classpath 和文件系统路径）
     */
    public Mono<AgentSpec> loadAgentSpec(Path agentFile) {
        return Mono.fromCallable(() -> {
            Path cacheKey = toCacheKey(agentFile);
            AgentSpec cached = specCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }

            log.info("正在加载Agent规范: {}", agentFile);
            AgentSpec agentSpec = doLoad(agentFile);
            validateSpec(agentSpec);
            specCache.put(cacheKey, agentSpec);
            return agentSpec;
        });
    }

    public Path getDefaultAgentPath() {
        if (agentsRootDir == null) {
            String resourcePath = AGENTS_RESOURCE_PREFIX + "default/agent.yaml";
            if (getClass().getClassLoader().getResource(resourcePath) != null) {
                return Paths.get(CLASSPATH_PREFIX + resourcePath);
            }
            throw new AgentSpecException("Default agent not found in classpath: " + resourcePath);
        }

        Path candidate = agentsRootDir.resolve(DEFAULT_AGENT_RELATIVE_PATH);
        if (Files.exists(candidate)) {
            return candidate.toAbsolutePath();
        }
        throw new AgentSpecException("Default agent not found in any expected location");
    }

    // ==================== 预加载 ====================

    private void preloadFromFileSystem() {
        if (!Files.exists(agentsRootDir) || !Files.isDirectory(agentsRootDir)) {
            return;
        }
        try {
            // 递归查找所有 agent.yaml，支持多级目录
            Files.walk(agentsRootDir)
                    .filter(path -> path.getFileName().toString().equals("agent.yaml"))
                    .forEach(yaml -> {
                        try {
                            loadAgentSpec(yaml).block();
                            log.debug("Preloaded agent spec: {}", yaml);
                        } catch (Exception e) {
                            log.warn("Failed to preload agent spec: {}", yaml, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to walk agents directory: {}", agentsRootDir, e);
        }
    }

    /**
     * 从类路径资源预加载（JAR包模式）
     * 使用 ** 通配符匹配所有层级的 agent.yaml
     */
    private void preloadFromClasspath() {
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:agents/**/agent.yaml");

            for (Resource resource : resources) {
                try {
                    String resourcePath = extractResourcePath(resource);
                    if (resourcePath == null) {
                        continue;
                    }
                    Path classpathKey = Paths.get(CLASSPATH_PREFIX + resourcePath);
                    loadAgentSpec(classpathKey).block();
                    log.debug("Preloaded agent spec from classpath: {}", resourcePath);
                } catch (Exception e) {
                    log.warn("Failed to preload agent spec from classpath: {}", resource, e);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan classpath for agents", e);
        }
    }

    // ==================== 核心加载逻辑（统一） ====================

    private AgentSpec doLoad(Path agentFile) {
        String pathStr = agentFile.toString();
        boolean isClasspath = pathStr.startsWith(CLASSPATH_PREFIX);

        try (InputStream inputStream = openInputStream(agentFile, isClasspath)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = yamlObjectMapper.readValue(inputStream, Map.class);

            String agentDirIdentifier = extractAgentDirIdentifier(agentFile, isClasspath);
            return parseAgentSpec(data, agentFile, agentDirIdentifier, isClasspath);
        } catch (IOException e) {
            throw new AgentSpecException("加载Agent规范失败: " + agentFile, e);
        }
    }

    private InputStream openInputStream(Path agentFile, boolean isClasspath) throws IOException {
        if (isClasspath) {
            String resourcePath = agentFile.toString().substring(CLASSPATH_PREFIX.length());
            InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath);
            if (stream == null) {
                throw new IOException("Classpath resource not found: " + resourcePath);
            }
            return stream;
        }

        Path absolute = agentFile.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            throw new AgentSpecException("Agent文件不存在: " + absolute);
        }
        return Files.newInputStream(absolute);
    }

    // ==================== 解析逻辑（统一） ====================

    @SuppressWarnings("unchecked")
    private AgentSpec parseAgentSpec(Map<String, Object> data, Path agentFile,
                                     String agentDirIdentifier, boolean isClasspath) {
        AgentSpec.AgentSpecBuilder builder = AgentSpec.builder();

        populateCommonFields(builder, data);
        resolveSystemPromptPath(builder, data, agentFile, agentDirIdentifier, isClasspath);
        resolveSubagents(builder, data, isClasspath);
        resolveTeam(builder, data, isClasspath);

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private void populateCommonFields(AgentSpec.AgentSpecBuilder builder, Map<String, Object> data) {
        if (data.containsKey("name")) {
            builder.name((String) data.get("name"));
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
        if (data.containsKey("model")) {
            builder.model((String) data.get("model"));
        }
    }

    private void resolveSystemPromptPath(AgentSpec.AgentSpecBuilder builder, Map<String, Object> data,
                                          Path agentFile, String agentDirIdentifier, boolean isClasspath) {
        String promptFile = (String) data.getOrDefault("system_prompt",
                data.get("system_prompt_path"));
        if (promptFile == null) {
            return;
        }

        Path promptPath;
        if (isClasspath) {
            promptPath = Paths.get(CLASSPATH_PREFIX + AGENTS_RESOURCE_PREFIX + agentDirIdentifier + "/" + promptFile);
        } else {
            promptPath = agentFile.getParent().resolve(promptFile);
        }
        builder.systemPromptPath(promptPath);
    }

    @SuppressWarnings("unchecked")
    private void resolveSubagents(AgentSpec.AgentSpecBuilder builder, Map<String, Object> data, boolean isClasspath) {
        if (!data.containsKey("subagents")) {
            return;
        }

        Map<String, SubagentSpec> subagents = new HashMap<>();
        Map<String, Map<String, Object>> subagentsData = (Map<String, Map<String, Object>>) data.get("subagents");

        for (Map.Entry<String, Map<String, Object>> entry : subagentsData.entrySet()) {
            String subagentPathStr = (String) entry.getValue().get("path");
            Path resolvedPath = resolvePath(subagentPathStr, isClasspath);

            SubagentSpec subagent = SubagentSpec.builder()
                    .path(resolvedPath)
                    .description((String) entry.getValue().get("description"))
                    .build();
            subagents.put(entry.getKey(), subagent);
        }
        builder.subagents(subagents);
    }

    @SuppressWarnings("unchecked")
    private void resolveTeam(AgentSpec.AgentSpecBuilder builder, Map<String, Object> data, boolean isClasspath) {
        if (!data.containsKey("team")) {
            return;
        }
        TeamSpec teamSpec = parseTeamSpec((Map<String, Object>) data.get("team"), isClasspath);
        builder.team(teamSpec);
    }

    @SuppressWarnings("unchecked")
    private TeamSpec parseTeamSpec(Map<String, Object> teamData, boolean isClasspath) {
        TeamSpec.TeamSpecBuilder builder = TeamSpec.builder();

        if (teamData.containsKey("name")) {
            builder.name((String) teamData.get("name"));
        }
        if (teamData.containsKey("max_concurrency")) {
            builder.maxConcurrency(((Number) teamData.get("max_concurrency")).intValue());
        }
        if (teamData.containsKey("timeout_seconds")) {
            builder.timeoutSeconds(((Number) teamData.get("timeout_seconds")).longValue());
        }
        if (teamData.containsKey("strategy")) {
            builder.strategy(TeamStrategy.fromString((String) teamData.get("strategy")));
        }

        if (teamData.containsKey("teammates")) {
            List<Map<String, Object>> teammatesData = (List<Map<String, Object>>) teamData.get("teammates");
            List<TeammateSpec> teammates = new ArrayList<>();

            for (Map<String, Object> tmData : teammatesData) {
                TeammateSpec.TeammateSpecBuilder tmBuilder = TeammateSpec.builder();
                if (tmData.containsKey("teammate_id")) {
                    tmBuilder.teammateId((String) tmData.get("teammate_id"));
                }
                if (tmData.containsKey("description")) {
                    tmBuilder.description((String) tmData.get("description"));
                }
                if (tmData.containsKey("specialties")) {
                    tmBuilder.specialties((List<String>) tmData.get("specialties"));
                }
                if (tmData.containsKey("agent_path")) {
                    tmBuilder.agentPath(resolvePath((String) tmData.get("agent_path"), isClasspath));
                }
                teammates.add(tmBuilder.build());
            }
            builder.teammates(teammates);
        }

        if (teamData.containsKey("initial_tasks")) {
            List<Map<String, Object>> tasksData = (List<Map<String, Object>>) teamData.get("initial_tasks");
            List<TeamTaskSpec> initialTasks = new ArrayList<>();

            for (Map<String, Object> taskData : tasksData) {
                TeamTaskSpec.TeamTaskSpecBuilder taskBuilder = TeamTaskSpec.builder();
                if (taskData.containsKey("description")) {
                    taskBuilder.description((String) taskData.get("description"));
                }
                if (taskData.containsKey("priority")) {
                    taskBuilder.priority(((Number) taskData.get("priority")).intValue());
                }
                if (taskData.containsKey("dependencies")) {
                    taskBuilder.dependencies((List<String>) taskData.get("dependencies"));
                }
                if (taskData.containsKey("preferred_teammate")) {
                    taskBuilder.preferredTeammate((String) taskData.get("preferred_teammate"));
                }
                initialTasks.add(taskBuilder.build());
            }
            builder.initialTasks(initialTasks);
        }

        return builder.build();
    }

    // ==================== 工具方法 ====================

    private static Path resolveAgentsDir() {
        if (isRunningFromJar()) {
            log.debug("Running from JAR, agents will be loaded from classpath resources");
            return null;
        }
        try {
            URL resource = AgentSpecLoader.class.getClassLoader().getResource("agents");
            if (resource != null) {
                return Paths.get(resource.toURI());
            }
        } catch (Exception e) {
            log.warn("无法从类路径加载agents目录，使用用户目录", e);
        }
        return Paths.get(System.getProperty("user.home"), ".jimi", "agents");
    }

    private static boolean isRunningFromJar() {
        try {
            URL resource = AgentSpecLoader.class.getClassLoader().getResource("agents");
            return resource != null && "jar".equals(resource.getProtocol());
        } catch (Exception e) {
            return false;
        }
    }

    private Path toCacheKey(Path agentFile) {
        String pathStr = agentFile.toString();
        if (pathStr.startsWith(CLASSPATH_PREFIX)) {
            return Paths.get(pathStr.substring(CLASSPATH_PREFIX.length()));
        }
        return agentFile.toAbsolutePath().normalize();
    }

    /**
     * 提取 agent 目录标识符（用于构造 classpath 路径）
     * 例如 "classpath:agents/sub/code/agent.yaml" -> "sub/code"
     * 例如 "/abs/path/agents/default/agent.yaml" -> 不使用（文件系统模式直接用 parent）
     */
    private String extractAgentDirIdentifier(Path agentFile, boolean isClasspath) {
        if (!isClasspath) {
            return null;
        }
        String pathStr = agentFile.toString().substring(CLASSPATH_PREFIX.length());
        // 格式: agents/{dirIdentifier}/agent.yaml
        if (pathStr.startsWith(AGENTS_RESOURCE_PREFIX)) {
            String afterAgents = pathStr.substring(AGENTS_RESOURCE_PREFIX.length());
            int lastSlash = afterAgents.lastIndexOf('/');
            return lastSlash > 0 ? afterAgents.substring(0, lastSlash) : afterAgents;
        }
        return "default";
    }

    /**
     * 从 Spring Resource 中提取 classpath 相对路径
     * 例如 "jar:file:/app.jar!/agents/sub/code/agent.yaml" -> "agents/sub/code/agent.yaml"
     */
    private String extractResourcePath(Resource resource) throws IOException {
        String urlPath = resource.getURL().toString();
        int agentsIdx = urlPath.indexOf(AGENTS_RESOURCE_PREFIX);
        if (agentsIdx == -1) {
            return null;
        }
        return urlPath.substring(agentsIdx);
    }

    /**
     * 根据运行模式解析路径
     */
    private Path resolvePath(String relativePath, boolean isClasspath) {
        if (isClasspath) {
            return Paths.get(CLASSPATH_PREFIX + AGENTS_RESOURCE_PREFIX + relativePath);
        }
        return agentsRootDir.resolve(relativePath);
    }

    private void validateSpec(AgentSpec agentSpec) {
        if (agentSpec.getName() == null || agentSpec.getName().isEmpty()) {
            throw new AgentSpecException("Agent名称不能为空");
        }
        if (agentSpec.getSystemPromptPath() == null) {
            throw new AgentSpecException("系统提示词路径不能为空");
        }
        if (agentSpec.getTools() == null) {
            throw new AgentSpecException("工具列表不能为空");
        }
    }

    public Map<Path, AgentSpec> getSpecCache() {
        return Collections.unmodifiableMap(specCache);
    }
}
