package io.leavesfly.jimi.agent;

import io.leavesfly.jimi.exception.AgentSpecException;
import io.leavesfly.jimi.soul.runtime.BuiltinSystemPromptArgs;
import io.leavesfly.jimi.soul.runtime.Runtime;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.apache.commons.text.StringSubstitutor;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

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
 */
@Slf4j
@Service
public class AgentRegistry {


    @Autowired
    private AgentSpecLoader specLoader;

    /**
     * 构造函数（由 Spring 管理）
     */
    @Autowired
    public AgentRegistry(AgentSpecLoader specLoader) {
        this.specLoader = specLoader;
    }


    /**
     * 加载 Agent 规范
     * 如果已缓存则直接返回缓存的规范
     *
     * @param agentFile Agent 配置文件路径（可以是相对路径或绝对路径）
     * @return 已解析的 Agent 规范
     */
    public Mono<AgentSpec> loadAgentSpec(Path agentFile) {

        return specLoader.loadAgentSpec(agentFile);
    }


    public Mono<Agent> loadAgent(Path agentFile, Runtime runtime) {
        return Mono.defer(() -> {

            // 规范化路径
            Path absolutePath = agentFile != null ? agentFile.toAbsolutePath().normalize() : specLoader.getDefaultAgentPath();

            // 加载 Agent 规范
            return loadAgentSpec(absolutePath).flatMap(spec -> {
                log.info("加载Agent: {} (from {})", spec.getName(), absolutePath);

                // 渲染系统提示词
                String systemPrompt = renderSystemPrompt(spec.getSystemPromptPath(), spec.getSystemPromptArgs(), runtime.getBuiltinArgs());

                // 处理工具列表
                List<String> tools = spec.getTools();
                if (spec.getExcludeTools() != null && !spec.getExcludeTools().isEmpty()) {
                    log.debug("排除工具: {}", spec.getExcludeTools());
                    tools = tools.stream().filter(tool -> !spec.getExcludeTools().contains(tool)).collect(Collectors.toList());
                }

                // 构建Agent实例
                Agent agent = Agent.builder().name(spec.getName()).systemPrompt(systemPrompt).tools(tools).build();


                return Mono.just(agent);
            });
        });
    }

    /**
     * 渲染系统提示词（基于预加载的模板）
     *
     * @param promptPath  提示词文件路径
     * @param args        自定义参数
     * @param builtinArgs 内置参数
     * @return 替换后的系统提示词
     */
    private String renderSystemPrompt(Path promptPath, Map<String, String> args, BuiltinSystemPromptArgs builtinArgs) {

        Path absolutePath = promptPath.toAbsolutePath().normalize();

        String template = null;
        try {
            template = Files.readString(absolutePath).strip();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // 准备替换参数
        Map<String, String> substitutionMap = new HashMap<>();

        // 添加内置参数
        substitutionMap.put("JIMI_NOW", builtinArgs.getJimiNow());
        substitutionMap.put("JIMI_WORK_DIR", builtinArgs.getJimiWorkDir().toString());
        substitutionMap.put("JIMI_WORK_DIR_LS", builtinArgs.getJimiWorkDirLs());
        substitutionMap.put("JIMI_AGENTS_MD", builtinArgs.getJimiAgentsMd());

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
        return loadAgent(specLoader.getDefaultAgentPath(), runtime);
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
     * 列出所有可用的 Agent 名称
     *
     * @return 可用的 Agent 名称列表
     */
    public List<String> listAvailableAgents() {
        Map<Path, AgentSpec> specCache = specLoader.getSpecCache();

        return specCache.values().stream()
                .map(AgentSpec::getName)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 获取所有 Agent 规范缓存
     * 
     * @return Agent 规范缓存映射（路径 -> 规范）
     */
    public Map<Path, AgentSpec> getAllAgentSpecs() {
        return specLoader.getSpecCache();
    }

    /**
     * 获取 AgentSpecLoader（用于访问缓存）
     * 
     * @return AgentSpecLoader 实例
     */
    AgentSpecLoader getSpecLoader() {
        return specLoader;
    }

    /**
     * 根据名称查找 Agent 配置文件路径
     * 
     * @param agentName Agent 名称
     * @return Agent 配置文件路径，如果未找到返回 null
     */
    public Path findAgentPathByName(String agentName) {
        return specLoader.getSpecCache().entrySet().stream()
                .filter(entry -> agentName.equals(entry.getValue().getName()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

}
