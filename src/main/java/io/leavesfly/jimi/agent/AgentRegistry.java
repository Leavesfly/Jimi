package io.leavesfly.jimi.agent;

import io.leavesfly.jimi.exception.AgentSpecException;
import io.leavesfly.jimi.soul.runtime.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.soul.runtime.Runtime;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.apache.commons.text.StringSubstitutor;
import reactor.core.publisher.Mono;
import jakarta.annotation.PostConstruct;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
     * agents 根目录
     */
    private final Path agentsRootDir;
    
    /**
     * AgentSpecLoader Bean
     */
    private  AgentSpecLoader specLoader;

    /**
     * 构造函数（由 Spring 管理）
     */
    @Autowired
    public AgentRegistry(AgentSpecLoader specLoader) {
        this.specLoader = specLoader;
        this.agentsRootDir = resolveAgentsDirectory();
        log.info("Agent Registry initialized, agents root: {}", agentsRootDir);
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
    
            specLoader.loadAgentSpec(agentFile);
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

        
            // 加载 Agent 规范
            return loadAgentSpec(absolutePath)
                    .flatMap(spec -> {
                        log.info("加载Agent: {} (from {})", spec.getName(), absolutePath);
                        
                        // 渲染系统提示词
                        String systemPrompt = renderSystemPrompt(
                                spec.getSystemPromptPath(),
                                spec.getSystemPromptArgs(),
                                runtime.getBuiltinArgs()
                        );
                        
                        // 处理工具列表
                        List<String> tools = spec.getTools();
                        if (spec.getExcludeTools() != null && !spec.getExcludeTools().isEmpty()) {
                            log.debug("排除工具: {}", spec.getExcludeTools());
                            tools = tools.stream()
                                    .filter(tool -> !spec.getExcludeTools().contains(tool))
                                    .collect(Collectors.toList());
                        }
                        
                        // 构建Agent实例
                        Agent agent = Agent.builder()
                                .name(spec.getName())
                                .systemPrompt(systemPrompt)
                                .tools(tools)
                                .build();
                        
                        
                        return Mono.just(agent);
                    });
        });
    }
    
    /**
     * 渲染系统提示词（基于预加载的模板）
     * 
     * @param promptPath 提示词文件路径
     * @param args 自定义参数
     * @param builtinArgs 内置参数
     * @return 替换后的系统提示词
     */
    private String renderSystemPrompt(
            Path promptPath,
            Map<String, String> args,
            BuiltinSystemPromptArgs builtinArgs
    ) {
        Path absolutePath = promptPath.toAbsolutePath().normalize();
        
        // 从缓存取模板，不存在则加载
        String template  = Files.readString(absolutePath).strip();
        
        // 准备替换参数
        Map<String, String> substitutionMap = new HashMap<>();
        
        // 添加内置参数
        substitutionMap.put("KIMI_NOW", builtinArgs.getKimiNow());
        substitutionMap.put("KIMI_WORK_DIR", builtinArgs.getKimiWorkDir().toString());
        substitutionMap.put("KIMI_WORK_DIR_LS", builtinArgs.getKimiWorkDirLs());
        substitutionMap.put("KIMI_AGENTS_MD", builtinArgs.getKimiAgentsMd());
        
        // 添加自定义参数（覆盖内置参数）
        if (args != null) {
            substitutionMap.putAll(args);
        }
        
        log.debug("渲染系统提示词: {}", absolutePath);
        
        // 执行字符串替换
        StringSubstitutor substitutor = new StringSubstitutor(substitutionMap);
        return substitutor.replace(template);
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
