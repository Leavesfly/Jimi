package io.leavesfly.jimi.core.agent;

import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.exception.AgentSpecException;
import io.leavesfly.jimi.core.engine.context.BuiltinSystemPromptArgs;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.apache.commons.text.StringSubstitutor;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import java.util.Objects;
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

        if (Objects.isNull(agentFile)) {
            agentFile = specLoader.getDefaultAgentPath();
        }

        return specLoader.loadAgentSpec(agentFile);
    }


    public Mono<Agent> loadAgent(Path agentFile, JimiRuntime jimiRuntime) {
        return Mono.defer(() -> {

            // 规范化路径 - 处理classpath资源和文件系统路径
            Path absolutePath;
            if (agentFile != null) {
                String pathStr = agentFile.toString();
                if (pathStr.startsWith("classpath:")) {
                    // classpath资源不需要toAbsolutePath
                    absolutePath = agentFile;
                } else {
                    absolutePath = agentFile.toAbsolutePath().normalize();
                }
            } else {
                absolutePath = specLoader.getDefaultAgentPath();
            }

            // 加载 Agent 规范
            return loadAgentSpec(absolutePath).flatMap(spec -> {
                log.info("加载Agent: {} (from {})", spec.getName(), absolutePath);

                // 渲染系统提示词（支持内联 prompt 和外部文件两种来源）
                String systemPrompt = renderSystemPrompt(
                        spec.getSystemPromptPath(),
                        spec.getSystemPromptArgs(),
                        jimiRuntime.getBuiltinArgs(),
                        spec.getInlineSystemPrompt());

                // 处理工具列表（同时考虑 exclude_tools 和 disallowedTools）
                List<String> tools = spec.getTools();
                List<String> excluded = new java.util.ArrayList<>();
                if (spec.getExcludeTools() != null && !spec.getExcludeTools().isEmpty()) {
                    excluded.addAll(spec.getExcludeTools());
                }
                if (spec.getDisallowedTools() != null && !spec.getDisallowedTools().isEmpty()) {
                    excluded.addAll(spec.getDisallowedTools());
                }
                if (!excluded.isEmpty()) {
                    log.debug("排除工具: {}", excluded);
                    tools = tools.stream().filter(tool -> !excluded.contains(tool)).collect(Collectors.toList());
                }

                // 构建Agent实例
                Agent agent = Agent.builder()
                        .name(spec.getName())
                        .systemPrompt(systemPrompt)
                        .model(spec.getModel())
                        .tools(tools)
                        .build();


                return Mono.just(agent);
            });
        });
    }

    /**
     * 渲染系统提示词（支持外部文件和内联 prompt 两种来源）。
     *
     * <p>当 {@code inlineSystemPrompt} 非空时（来自 .md 文件的 body），
     * 直接使用它作为模板；否则从 {@code promptPath} 读取文件。
     *
     * @param promptPath        提示词文件路径（inlineSystemPrompt 为空时使用）
     * @param args              自定义参数
     * @param builtinArgs       内置参数
     * @param inlineSystemPrompt 内联系统提示词（来自 .md body），非空时优先使用
     * @return 替换后的系统提示词
     */
    private String renderSystemPrompt(Path promptPath, Map<String, String> args,
                                       BuiltinSystemPromptArgs builtinArgs, String inlineSystemPrompt) {

        String template;
        if (inlineSystemPrompt != null && !inlineSystemPrompt.isBlank()) {
            // .md 格式：直接使用 body 内容作为模板
            template = inlineSystemPrompt.strip();
        } else {
            // agent.yaml 格式：从文件读取
            if (promptPath == null) {
                throw new RuntimeException("System prompt path is null and no inline prompt provided");
            }
            try {
                String pathStr = promptPath.toString();
                if (pathStr.startsWith("classpath:")) {
                    String resourcePath = pathStr.substring("classpath:".length());
                    InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
                    if (is == null) {
                        throw new RuntimeException("Classpath resource not found: " + resourcePath);
                    }
                    template = new String(is.readAllBytes()).strip();
                } else {
                    Path absolutePath = promptPath.toAbsolutePath().normalize();
                    template = Files.readString(absolutePath).strip();
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to load system prompt from: " + promptPath, e);
            }
        }

        // 准备替换参数
        Map<String, String> substitutionMap = new HashMap<>();

        // 添加内置参数
        substitutionMap.put("JIMI_NOW", builtinArgs.getJimiNow());
        substitutionMap.put("JIMI_WORK_DIR", builtinArgs.getJimiWorkDir().toString());
        substitutionMap.put("JIMI_WORK_DIR_LS", builtinArgs.getJimiWorkDirLs());
        substitutionMap.put("JIMI_AGENTS_MD", builtinArgs.getJimiAgentsMd());
        
        // 添加技能摘要（渐进式披露）
        String skillsSummary = builtinArgs.getJimiSkillsSummary();
        substitutionMap.put("JIMI_SKILLS_SUMMARY", skillsSummary != null ? skillsSummary : "");

        // 添加长期记忆摘要
        String memorySummary = builtinArgs.getJimiMemorySummary();
        substitutionMap.put("JIMI_MEMORY_SUMMARY", memorySummary != null ? memorySummary : "");

        // 添加自定义参数（覆盖内置参数）
        if (args != null) {
            substitutionMap.putAll(args);
        }

        log.debug("渲染系统提示词: {}", promptPath);

        // 执行字符串替换
        StringSubstitutor substitutor = new StringSubstitutor(substitutionMap);
        return substitutor.replace(template);
    }

    /**
     * 加载默认 Agent 实例
     *
     * @param jimiRuntime 运行时上下文
     * @return 默认 Agent 实例
     */
    public Mono<Agent> loadDefaultAgent(JimiRuntime jimiRuntime) {
        return loadAgent(specLoader.getDefaultAgentPath(), jimiRuntime);
    }


    /**
     * 加载 Subagent 实例
     *
     * @param subagentSpec Subagent 规范
     * @param jimiRuntime      运行时上下文
     * @return Agent 实例
     */
    public Mono<Agent> loadSubagent(SubagentSpec subagentSpec, JimiRuntime jimiRuntime) {
        if (subagentSpec == null || subagentSpec.getPath() == null) {
            return Mono.error(new AgentSpecException("Invalid subagent spec"));
        }

        return loadAgent(subagentSpec.getPath(), jimiRuntime);
    }


    /**
     * 列出所有可用的 Agent 名称
     *
     * @return 可用的 Agent 名称列表
     */
    public List<String> listAvailableAgents() {
        Map<Path, AgentSpec> specCache = specLoader.getSpecCache();

        return specCache.values().stream().map(AgentSpec::getName).sorted().collect(Collectors.toList());
    }

    /**
     * 获取所有 Agent 规范缓存
     *
     * @return Agent 规范缓存映射（路径 -> 规范）
     */
    public Map<Path, AgentSpec> getAllAgentSpecs() {
        return specLoader.getSpecCache();
    }

    // ==================== 插件系统动态注册 ====================

    /**
     * 动态注册一个 Agent 规范（从文件加载并缓存）。
     *
     * <p>用于插件系统在运行时注入 Agent。与启动时 {@code @PostConstruct}
     * 预加载的效果一致——解析 agent.yaml 并写入 specCache，后续
     * {@link #loadAgent(Path, JimiRuntime)} 可直接命中缓存。
     *
     * <p>若同名 Agent 已缓存，新规范会覆盖旧的（先 evict 再 put）。
     *
     * @param agentFile Agent 配置文件路径（{@code agent.yaml} 的绝对路径）
     * @return 加载后的 AgentSpec
     */
    public Mono<AgentSpec> registerAgentSpec(Path agentFile) {
        if (agentFile == null) {
            return Mono.error(new AgentSpecException("agentFile cannot be null"));
        }
        log.info("Registering agent spec from plugin: {}", agentFile);
        // 先驱逐旧缓存（覆盖语义），再加载新规范
        specLoader.evictFromCache(agentFile);
        return specLoader.loadAgentSpec(agentFile);
    }

    /**
     * 反注册一个 Agent 规范（仅内存层面，不碰磁盘）。
     *
     * <p>用于插件系统在 {@code /plugin disable} 或 {@code /plugin uninstall}
     * 时回退注册动作。与 {@link #registerAgentSpec(Path)} 对称。
     *
     * @param agentFile Agent 配置文件路径（需与注册时传入的路径一致）
     * @return 是否成功移除（缓存中不存在时返回 {@code false}）
     */
    public boolean unregisterAgentSpec(Path agentFile) {
        if (agentFile == null) {
            return false;
        }
        return specLoader.evictFromCache(agentFile) != null;
    }
}
