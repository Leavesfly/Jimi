package io.leavesfly.jimi.agent;

import io.leavesfly.jimi.exception.AgentSpecException;
import io.leavesfly.jimi.soul.runtime.Runtime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 注册表（Spring Service）
 * 集中管理所有可用的代理（Agents），封装 AgentSpecLoader 的实现细节
 * <p>
 * 职责：
 * - 提供统一的 Agent 加载接口
 * - 缓存已加载的 Agent 规范和实例
 * - 管理默认 Agent 和自定义 Agent
 * - 提供 Agent 查询和检索功能
 * <p>
 * 缓存策略说明：
 * 1. **Agent 无状态设计**：Agent 对象只包含配置数据（name、systemPrompt、tools），不包含运行时状态
 * 2. **缓存安全性**：由于 Agent 是不可变的配置对象，可以安全地在多个 Runtime 之间共享
 * 3. **缓存键**：使用 Agent 配置文件的绝对路径作为缓存键
 * 4. **Runtime 独立性**：虽然 loadAgent() 接受 Runtime 参数（用于系统提示词渲染），
 * 但 Agent 本身不持有 Runtime 引用，因此缓存的 Agent 可复用
 * 5. **线程安全**：使用 ConcurrentHashMap 确保多线程环境下的缓存安全
 * 6. **缓存失效**：提供 reload() 和 clearCache() 方法支持配置热更新
 * <p>
 * 线程安全：由 Spring 管理的单例 Bean，内部使用 ConcurrentHashMap 确保线程安全
 *
 * @author Jimi Team
 */
@Slf4j
@Service
public class AgentRegistry {

    /**
     * 默认 Agent 名称
     */
    private static final String DEFAULT_AGENT_NAME = "default";

    /**
     * 默认 Agent 文件路径（相对于 agents 目录）
     */
    private static final Path DEFAULT_AGENT_RELATIVE_PATH =
            Paths.get("default", "agent.yaml");

    /**
     * 已加载的 Agent 规范缓存（线程安全）
     * <p>
     * Key: Agent 配置文件路径（绝对路径）
     * Value: 已解析的 Agent 规范
     * <p>
     * 缓存理由：Agent 规范是纯配置数据，可安全共享
     */
    private final Map<Path, ResolvedAgentSpec> specCache = new ConcurrentHashMap<>();

    /**
     * 已加载的 Agent 实例缓存（线程安全）
     * <p>
     * Key: Agent 配置文件路径（绝对路径）
     * Value: 完整的 Agent 实例（包含渲染后的系统提示词）
     * <p>
     * 缓存理由：
     * - Agent 是不可变的数据对象（name、systemPrompt、tools）
     * - 不包含任何运行时状态或会话信息
     * - systemPrompt 虽然是运行时渲染的，但对于同一配置文件，渲染结果通常相同
     * - 可安全地在多个 Session/Runtime 之间共享
     * <p>
     * 注意事项：
     * - 如果 Agent 配置文件变更，需调用 reload() 清除缓存
     * - systemPrompt 中如果包含动态内容（如时间戳），可能需要考虑缓存失效策略
     */
    private final Map<Path, Agent> agentCache = new ConcurrentHashMap<>();

    /**
     * agents 根目录
     */
    private final Path agentsRootDir;

    /**
     * 构造函数（由 Spring 管理）
     */
    public AgentRegistry() {
        this.agentsRootDir = resolveAgentsDirectory();
        log.info("Agent Registry initialized, agents root: {}", agentsRootDir);
    }


    /**
     * 重新加载所有 Agent 配置
     * 清除所有缓存并重新扫描 agents 目录
     * <p>
     * 使用场景：
     * - Agent 配置文件已修改，需要重新加载
     * - 系统提示词模板已更新
     * - 开发环境下的热更新
     * <p>
     * 注意：此操作会清除所有缓存，下次加载时将从文件重新读取
     */
    public void reload() {
        log.info("Reloading all agent configurations...");

        int specCount = specCache.size();
        int agentCount = agentCache.size();

        // 清除所有缓存
        specCache.clear();
        agentCache.clear();

        log.info("Agent cache cleared: {} specs, {} agents", specCount, agentCount);
        log.info("Available agents after reload: {}", listAvailableAgents());
    }

    /**
     * 解析 agents 目录位置
     * 优先从类路径加载，回退到相对路径
     */
    private Path resolveAgentsDirectory() {
        try {
            var resource = AgentRegistry.class.getClassLoader().getResource("agents");
            if (resource != null) {
                Path path = Paths.get(resource.toURI());
                log.debug("Found agents directory in classpath: {}", path);
                return path;
            }
        } catch (Exception e) {
            log.debug("Cannot load agents directory from classpath, using relative path", e);
        }

        // 回退到相对路径
        Path relativePath = Paths.get("src/main/resources/agents");
        log.debug("Using relative agents directory: {}", relativePath);
        return relativePath;
    }

    /**
     * 获取默认 Agent 配置文件路径
     *
     * @return 默认 Agent 配置文件的绝对路径
     * @throws AgentSpecException 如果默认 Agent 不存在
     */
    private Path getDefaultAgentPath() {
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

    /**
     * 加载 Agent 规范
     * 如果已缓存则直接返回缓存的规范
     *
     * @param agentFile Agent 配置文件路径（可以是相对路径或绝对路径）
     * @return 已解析的 Agent 规范
     */
    public Mono<ResolvedAgentSpec> loadAgentSpec(Path agentFile) {
        return Mono.defer(() -> {
            // 规范化路径（转为绝对路径）
            Path absolutePath = agentFile.toAbsolutePath().normalize();

            // 检查缓存
            ResolvedAgentSpec cached = specCache.get(absolutePath);
            if (cached != null) {
                log.debug("Agent spec cache hit: {}", absolutePath);
                return Mono.just(cached);
            }

            // 使用 AgentSpecLoader 加载
            log.debug("Loading agent spec from: {}", absolutePath);
            return AgentSpecLoader.loadAgentSpec(absolutePath)
                    .doOnSuccess(spec -> {
                        // 缓存加载的规范
                        specCache.put(absolutePath, spec);
                        log.debug("Agent spec cached: {}", absolutePath);
                    });
        });
    }

    /**
     * 加载默认 Agent 规范
     *
     * @return 默认 Agent 的规范
     */
    public Mono<ResolvedAgentSpec> loadDefaultAgentSpec() {
        return loadAgentSpec(getDefaultAgentPath());
    }

    /**
     * 加载 Agent 实例
     * 如果已缓存则直接返回缓存的实例
     * <p>
     * 缓存策略说明：
     * - Agent 是无状态的配置对象，可安全缓存和共享
     * - 虽然方法接受 Runtime 参数，但仅用于系统提示词渲染，不影响 Agent 本身的状态
     * - 对于相同的配置文件，无论 Runtime 如何，返回的 Agent 配置是一致的
     * - 如果需要每次都重新加载（例如配置文件频繁变更），请先调用 reload()
     *
     * @param agentFile Agent 配置文件路径
     * @param runtime   运行时上下文（用于系统提示词渲染）
     * @return 完整的 Agent 实例（包含系统提示词）
     */
    public Mono<Agent> loadAgent(Path agentFile, Runtime runtime) {
        return Mono.defer(() -> {
            // 规范化路径
            Path absolutePath = agentFile != null
                    ? agentFile.toAbsolutePath().normalize()
                    : getDefaultAgentPath();

            // 检查缓存
            // Agent 是不可变配置对象，缓存安全，可在不同 Runtime 间共享
            Agent cached = agentCache.get(absolutePath);
            if (cached != null) {
                log.debug("Agent cache hit: {}", absolutePath);
                return Mono.just(cached);
            }

            // 使用 AgentSpecLoader 加载
            log.debug("Loading agent instance from: {}", absolutePath);
            return AgentSpecLoader.loadAgent(absolutePath, runtime)
                    .doOnSuccess(agent -> {
                        // 缓存加载的实例
                        agentCache.put(absolutePath, agent);
                        log.debug("Agent cached: {}", absolutePath);
                    });
        });
    }

    /**
     * 加载默认 Agent 实例
     *
     * @param runtime 运行时上下文
     * @return 默认 Agent 实例
     */
    public Mono<Agent> loadDefaultAgent(Runtime runtime) {
        return loadAgent(getDefaultAgentPath(), runtime);
    }

    /**
     * 根据名称获取预定义 Agent 的路径
     *
     * @param agentName Agent 名称（如 "build", "test", "debug", "research"）
     * @return Agent 配置文件路径
     * @throws AgentSpecException 如果指定的 Agent 不存在
     */
    private Path getAgentPath(String agentName) {
        if (agentName == null || agentName.isEmpty()) {
            return getDefaultAgentPath();
        }

        if (DEFAULT_AGENT_NAME.equals(agentName)) {
            return getDefaultAgentPath();
        }

        // 尝试在 agents 目录下查找
        Path agentPath = agentsRootDir.resolve(agentName).resolve("agent.yaml");

        if (!Files.exists(agentPath)) {
            throw new AgentSpecException("Agent not found: " + agentName);
        }

        return agentPath.toAbsolutePath();
    }

    /**
     * 检查指定的 Agent 是否存在
     *
     * @param agentName Agent 名称
     * @return 如果 Agent 存在返回 true
     */
    public boolean hasAgent(String agentName) {
        try {
            getAgentPath(agentName);
            return true;
        } catch (AgentSpecException e) {
            return false;
        }
    }

    /**
     * 根据名称加载 Agent 规范
     *
     * @param agentName Agent 名称
     * @return Agent 规范
     */
    public Mono<ResolvedAgentSpec> loadAgentSpecByName(String agentName) {
        return loadAgentSpec(getAgentPath(agentName));
    }

    /**
     * 根据名称加载 Agent 实例
     *
     * @param agentName Agent 名称
     * @param runtime   运行时上下文
     * @return Agent 实例
     */
    public Mono<Agent> loadAgentByName(String agentName, Runtime runtime) {
        return loadAgent(getAgentPath(agentName), runtime);
    }

    /**
     * 加载 Subagent 规范
     * 用于 Task 工具等需要加载子 Agent 的场景
     *
     * @param subagentSpec Subagent 规范（包含路径信息）
     * @return 已解析的 Agent 规范
     */
    public Mono<ResolvedAgentSpec> loadSubagentSpec(SubagentSpec subagentSpec) {
        if (subagentSpec == null || subagentSpec.getPath() == null) {
            return Mono.error(new AgentSpecException("Invalid subagent spec"));
        }

        return loadAgentSpec(subagentSpec.getPath());
    }

    /**
     * 加载 Subagent 实例
     *
     * @param subagentSpec Subagent 规范
     * @param runtime      运行时上下文
     * @return Agent 实例
     */
    public Mono<Agent> loadSubagent(SubagentSpec subagentSpec, Runtime runtime) {
        if (subagentSpec == null || subagentSpec.getPath() == null) {
            return Mono.error(new AgentSpecException("Invalid subagent spec"));
        }

        return loadAgent(subagentSpec.getPath(), runtime);
    }

    /**
     * 批量加载多个 Subagent 规范
     *
     * @param subagents Subagent 映射（名称 -> 规范）
     * @return Subagent 规范映射（名称 -> 已解析的规范）
     */
    public Mono<Map<String, ResolvedAgentSpec>> loadSubagentSpecs(
            Map<String, SubagentSpec> subagents
    ) {
        if (subagents == null || subagents.isEmpty()) {
            return Mono.just(Map.of());
        }

        Map<String, ResolvedAgentSpec> result = new ConcurrentHashMap<>();

        return Mono.when(
                subagents.entrySet().stream()
                        .map(entry ->
                                loadSubagentSpec(entry.getValue())
                                        .doOnSuccess(spec -> result.put(entry.getKey(), spec))
                                        .onErrorResume(e -> {
                                            log.error("Failed to load subagent: {}", entry.getKey(), e);
                                            return Mono.empty();
                                        })
                        )
                        .toList()
        ).thenReturn(result);
    }

    /**
     * 清除所有缓存（公共方法）
     * 用于强制重新加载 Agent 配置，例如配置文件变更后
     * <p>
     * 线程安全：ConcurrentHashMap 的 clear() 操作是原子的
     */
    public void clearCache() {
        int specCount = specCache.size();
        int agentCount = agentCache.size();

        specCache.clear();
        agentCache.clear();

        log.info("Agent cache cleared: {} specs, {} agents", specCount, agentCount);
    }

    /**
     * 清除指定 Agent 的缓存（公共方法）
     * 用于单个 Agent 配置更新后的增量刷新
     *
     * @param agentFile Agent 配置文件路径
     */
    public void clearCache(Path agentFile) {
        Path absolutePath = agentFile.toAbsolutePath().normalize();

        boolean specRemoved = specCache.remove(absolutePath) != null;
        boolean agentRemoved = agentCache.remove(absolutePath) != null;

        if (specRemoved || agentRemoved) {
            log.debug("Cache cleared for agent: {}", absolutePath);
        }
    }

    /**
     * 获取缓存统计信息
     *
     * @return 包含缓存大小的描述字符串
     */
    public String getCacheStats() {
        return String.format("AgentRegistry Cache - Specs: %d, Agents: %d",
                specCache.size(), agentCache.size());
    }

    /**
     * 获取 agents 根目录（内部使用）
     *
     * @return agents 根目录路径
     */
    private Path getAgentsRootDir() {
        return agentsRootDir;
    }

    /**
     * 列出所有可用的 Agent 名称
     * 扫描 agents 目录下的所有子目录
     *
     * @return 可用的 Agent 名称列表
     */
    private List<String> listAvailableAgents() {
        try {
            if (!Files.exists(agentsRootDir) || !Files.isDirectory(agentsRootDir)) {
                log.warn("Agents directory does not exist: {}", agentsRootDir);
                return List.of();
            }

            return Files.list(agentsRootDir)
                    .filter(Files::isDirectory)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> Files.exists(agentsRootDir.resolve(name).resolve("agent.yaml")))
                    .sorted()
                    .toList();

        } catch (Exception e) {
            log.error("Failed to list available agents", e);
            return List.of();
        }
    }

    /**
     * 获取 Agent 的描述信息
     * 从 Agent 规范中提取名称和描述
     *
     * @param agentName Agent 名称
     * @return Optional 包含 Agent 描述，如果不存在则为空
     */
    private Optional<String> getAgentDescription(String agentName) {
        try {
            ResolvedAgentSpec spec = loadAgentSpecByName(agentName).block();
            if (spec != null) {
                return Optional.of(spec.getName());
            }
        } catch (Exception e) {
            log.debug("Failed to get agent description: {}", agentName, e);
        }
        return Optional.empty();
    }
}
