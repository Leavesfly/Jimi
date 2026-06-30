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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
            // 递归查找 agent.yaml 和 agent.md（Claude Code 格式），支持多级目录
            Files.walk(agentsRootDir)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.equals("agent.yaml") || fileName.equals("agent.md");
                    })
                    .forEach(specFile -> {
                        try {
                            loadAgentSpec(specFile).block();
                            log.debug("Preloaded agent spec: {}", specFile);
                        } catch (Exception e) {
                            log.warn("Failed to preload agent spec: {}", specFile, e);
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
            // 同时扫描 agent.yaml 和 agent.md（Claude Code 格式）
            Resource[] yamlResources = resolver.getResources("classpath:agents/**/agent.yaml");
            Resource[] mdResources = resolver.getResources("classpath:agents/**/agent.md");

            for (Resource resource : yamlResources) {
                preloadClasspathResource(resource);
            }
            for (Resource resource : mdResources) {
                preloadClasspathResource(resource);
            }
        } catch (IOException e) {
            log.warn("Failed to scan classpath for agents", e);
        }
    }

    private void preloadClasspathResource(Resource resource) {
        try {
            String resourcePath = extractResourcePath(resource);
            if (resourcePath == null) {
                return;
            }
            Path classpathKey = Paths.get(CLASSPATH_PREFIX + resourcePath);
            loadAgentSpec(classpathKey).block();
            log.debug("Preloaded agent spec from classpath: {}", resourcePath);
        } catch (Exception e) {
            log.warn("Failed to preload agent spec from classpath: {}", resource, e);
        }
    }

    // ==================== 核心加载逻辑（统一） ====================

    private AgentSpec doLoad(Path agentFile) {
        String pathStr = agentFile.toString();
        boolean isClasspath = pathStr.startsWith(CLASSPATH_PREFIX);

        // 根据文件扩展名分派：.md 走 Claude Code frontmatter 解析，其余走 YAML 解析
        String fileName = pathStr;
        if (!isClasspath) {
            fileName = agentFile.getFileName().toString();
        }
        if (fileName.endsWith(".md")) {
            return doLoadMarkdown(agentFile, isClasspath);
        }

        try (InputStream inputStream = openInputStream(agentFile, isClasspath)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = yamlObjectMapper.readValue(inputStream, Map.class);

            String agentDirIdentifier = extractAgentDirIdentifier(agentFile, isClasspath);
            return parseAgentSpec(data, agentFile, agentDirIdentifier, isClasspath);
        } catch (IOException e) {
            throw new AgentSpecException("加载Agent规范失败: " + agentFile, e);
        }
    }

    /**
     * 解析 Claude Code 格式的 .md 文件（YAML frontmatter + Markdown body）。
     *
     * <p>文件格式：
     * <pre>
     * ---
     * name: code-reviewer
     * description: Reviews code for quality
     * tools: Read, Glob, Grep
     * ---
     *
     * You are a code reviewer...
     * </pre>
     *
     * <p>frontmatter 部分解析为配置，body 部分成为 inlineSystemPrompt。
     * 如果文件不以 {@code ---} 开头，说明不是 agent 定义，抛出异常跳过。
     *
     * @param mdFile     .md 文件路径
     * @param isClasspath 是否为 classpath 资源
     * @return 解析后的 AgentSpec
     */
    @SuppressWarnings("unchecked")
    private AgentSpec doLoadMarkdown(Path mdFile, boolean isClasspath) {
        try (InputStream inputStream = openInputStream(mdFile, isClasspath)) {
            String content = new String(inputStream.readAllBytes());

            // 提取 frontmatter 和 body
            String frontmatter;
            String body;
            String stripped = content.stripLeading();
            if (!stripped.startsWith("---")) {
                // 无 frontmatter 的 .md 文件不是 agent 定义，跳过
                throw new AgentSpecException("Not a valid agent markdown (no frontmatter): " + mdFile);
            }

            // 找到 frontmatter 的结束分隔符（第二个 ---）
            int firstSepEnd = stripped.indexOf('\n');
            if (firstSepEnd < 0) {
                throw new AgentSpecException("Malformed frontmatter in: " + mdFile);
            }
            int secondSep = stripped.indexOf("\n---", firstSepEnd);
            if (secondSep < 0) {
                throw new AgentSpecException("Missing closing frontmatter delimiter in: " + mdFile);
            }

            frontmatter = stripped.substring(firstSepEnd + 1, secondSep);
            // body 是第二个 --- 之后的内容
            int bodyStart = secondSep + 4; // skip "\n---"
            if (bodyStart < stripped.length()) {
                body = stripped.substring(bodyStart).strip();
            } else {
                body = "";
            }

            // 解析 frontmatter 为 Map
            Map<String, Object> data = yamlObjectMapper.readValue(frontmatter, Map.class);

            // 复用现有解析逻辑，但 system_prompt 来自 body
            String agentDirIdentifier = extractAgentDirIdentifier(mdFile, isClasspath);
            AgentSpec.AgentSpecBuilder builder = AgentSpec.builder();
            populateCommonFields(builder, data);
            resolveSubagents(builder, data, isClasspath);
            resolveTeam(builder, data, isClasspath);

            // body 内容设为内联系统提示词
            builder.inlineSystemPrompt(body);
            // systemPromptPath 设为 .md 文件本身，方便定位
            builder.systemPromptPath(mdFile);

            return builder.build();
        } catch (IOException e) {
            throw new AgentSpecException("加载Agent Markdown失败: " + mdFile, e);
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
        // Claude Code 标准必需字段
        if (data.containsKey("description")) {
            builder.description((String) data.get("description"));
        }
        if (data.containsKey("system_prompt_args")) {
            builder.systemPromptArgs((Map<String, String>) data.get("system_prompt_args"));
        }
        // tools 支持逗号分隔字符串（Claude Code 格式）和列表（Jimi 原生格式）
        if (data.containsKey("tools")) {
            builder.tools(parseToolsField(data.get("tools")));
        }
        if (data.containsKey("exclude_tools")) {
            builder.excludeTools(parseToolsField(data.get("exclude_tools")));
        }
        // Claude Code 标准：disallowedTools（与 excludeTools 等价的别名）
        if (data.containsKey("disallowedTools")) {
            builder.disallowedTools(parseToolsField(data.get("disallowedTools")));
        }
        // Claude Code 标准：maxTurns
        if (data.containsKey("maxTurns")) {
            Object maxTurnsVal = data.get("maxTurns");
            if (maxTurnsVal instanceof Number) {
                builder.maxTurns(((Number) maxTurnsVal).intValue());
            }
        }
        if (data.containsKey("model")) {
            builder.model((String) data.get("model"));
        }
    }

    /**
     * 解析 tools/excludeTools/disallowedTools 字段，兼容两种格式：
     * <ul>
     *   <li>列表格式（Jimi 原生）：{@code [Read, Glob, Grep]}</li>
     *   <li>逗号分隔字符串（Claude Code）：{@code "Read, Glob, Grep"}</li>
     * </ul>
     *
     * @param toolsValue 原始值
     * @return 解析后的工具名列表
     */
    @SuppressWarnings("unchecked")
    private List<String> parseToolsField(Object toolsValue) {
        if (toolsValue == null) {
            return Collections.emptyList();
        }
        if (toolsValue instanceof List) {
            return (List<String>) toolsValue;
        }
        if (toolsValue instanceof String s) {
            return Arrays.stream(s.split(","))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
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
        // systemPromptPath 和 inlineSystemPrompt 至少有一个（兼容 .md body 和 .yaml 外部引用）
        boolean hasPrompt = agentSpec.getSystemPromptPath() != null
                || (agentSpec.getInlineSystemPrompt() != null && !agentSpec.getInlineSystemPrompt().isBlank());
        if (!hasPrompt) {
            throw new AgentSpecException("系统提示词不能为空（需提供 system_prompt 或内联 prompt body）");
        }
        if (agentSpec.getTools() == null) {
            throw new AgentSpecException("工具列表不能为空");
        }
    }

    public Map<Path, AgentSpec> getSpecCache() {
        return Collections.unmodifiableMap(specCache);
    }

    /**
     * 从缓存中移除一个 Agent 规范（仅内存层面，不碰磁盘）。
     *
     * <p>调用方：{@link AgentRegistry#unregisterAgentSpec(Path)} → 插件卸载场景。
     * 与 {@link #loadAgentSpec(Path)} 对称，后者解析并缓存，本方法仅做缓存驱逐。
     *
     * @param agentFile Agent 配置文件路径（与 {@link #loadAgentSpec} 的参数一致）
     * @return 被移除的 AgentSpec；缓存中不存在时返回 {@code null}
     */
    public AgentSpec evictFromCache(Path agentFile) {
        Path cacheKey = toCacheKey(agentFile);
        AgentSpec removed = specCache.remove(cacheKey);
        if (removed != null) {
            log.info("Agent spec evicted from cache: {} (name={})", agentFile, removed.getName());
        }
        return removed;
    }
}
