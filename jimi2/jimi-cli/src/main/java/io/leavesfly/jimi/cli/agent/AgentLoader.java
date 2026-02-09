package io.leavesfly.jimi.cli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.agent.AgentSpec;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolProvider;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.engine.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Agent 加载器
 * <p>
 * 从文件系统加载 Agent 规范（YAML 格式），并实例化 Agent
 * </p>
 *
 * @author Jimi2 Team
 */
public class AgentLoader {
    
    private static final Logger log = LoggerFactory.getLogger(AgentLoader.class);
    
    /** Agent 规范目录 */
    private final Path agentsDir;
    
    /** 工作目录 */
    private final Path workDir;
    
    /** YAML 解析器 */
    private final ObjectMapper yamlMapper;
    
    /** 工具提供者缓存 */
    private List<ToolProvider> toolProviders;
    
    /**
     * 构造函数
     *
     * @param agentsDir Agent 规范目录
     * @param workDir   工作目录
     */
    public AgentLoader(Path agentsDir, Path workDir) {
        this.agentsDir = agentsDir;
        this.workDir = workDir;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.findAndRegisterModules();
    }
    
    /**
     * 加载所有 Agent
     *
     * @return Agent 列表
     */
    public List<Agent> loadAll() {
        List<Agent> agents = new ArrayList<>();
        
        // 确保目录存在
        if (!Files.exists(agentsDir)) {
            log.warn("Agent 目录不存在: {}", agentsDir);
            // 创建默认 Agent
            agents.add(createDefaultAgent());
            return agents;
        }
        
        // 扫描目录下的 YAML 文件
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(agentsDir, "*.{yaml,yml}")) {
            for (Path file : stream) {
                try {
                    Agent agent = loadFromFile(file);
                    if (agent != null) {
                        agents.add(agent);
                        log.info("已加载 Agent: {} from {}", agent.getName(), file.getFileName());
                    }
                } catch (Exception e) {
                    log.error("加载 Agent 文件失败: {}", file, e);
                }
            }
        } catch (IOException e) {
            log.error("扫描 Agent 目录失败", e);
        }
        
        // 如果没有找到任何 Agent，创建默认的
        if (agents.isEmpty()) {
            agents.add(createDefaultAgent());
        }
        
        return agents;
    }
    
    /**
     * 从文件加载 Agent
     *
     * @param file Agent 规范文件
     * @return Agent 实例
     */
    public Agent loadFromFile(Path file) throws IOException {
        // 解析 YAML 为 AgentSpec
        AgentSpec spec = yamlMapper.readValue(file.toFile(), AgentSpec.class);
        
        // 从 AgentSpec 创建 Agent
        return createAgentFromSpec(spec);
    }
    
    /**
     * 从规范创建 Agent
     *
     * @param spec Agent 规范
     * @return Agent 实例
     */
    private Agent createAgentFromSpec(AgentSpec spec) {
        // 加载工具
        List<Tool> tools = loadTools(spec);
        
        return Agent.builder()
                .name(spec.getName())
                .description(spec.getDescription())
                .version(spec.getVersion())
                .systemPrompt(spec.getSystemPrompt())
                .model(spec.getModel())
                .tools(tools)
                .toolNames(spec.getTools())
                .maxSteps(spec.getMaxSteps())
                .build();
    }
    
    /**
     * 加载工具
     *
     * @param spec Agent 规范
     * @return 工具列表
     */
    @SuppressWarnings("unchecked")
    private List<Tool> loadTools(AgentSpec spec) {
        List<String> toolNames = spec.getTools();
        
        // 加载所有工具提供者
        ensureToolProvidersLoaded();
        
        // 创建运行时环境（用于工具创建）
        RuntimeConfig runtimeConfig = RuntimeConfig.builder().workDir(workDir).build();
        Runtime runtime = Runtime.builder().config(runtimeConfig).build();
        
        List<Tool> allTools = new ArrayList<>();
        
        // 从每个提供者创建工具
        for (ToolProvider provider : toolProviders) {
            if (provider.supports(spec, runtime)) {
                List<Tool<?>> providerTools = provider.createTools(spec, runtime);
                for (Tool<?> tool : providerTools) {
                    allTools.add((Tool) tool);
                }
            }
        }
        
        // 如果指定了工具名称列表，进行过滤
        if (toolNames != null && !toolNames.isEmpty()) {
            Set<String> requestedTools = new HashSet<>(toolNames);
            allTools = allTools.stream()
                    .filter(t -> requestedTools.contains(t.getName()))
                    .collect(java.util.stream.Collectors.toList());
        }
        
        return allTools;
    }
    
    /**
     * 确保工具提供者已加载
     */
    private void ensureToolProvidersLoaded() {
        if (toolProviders == null) {
            toolProviders = new ArrayList<>();
            // 使用 SPI 加载所有工具提供者
            ServiceLoader<ToolProvider> loader = ServiceLoader.load(ToolProvider.class);
            for (ToolProvider provider : loader) {
                toolProviders.add(provider);
                log.debug("已加载工具提供者: {}", provider.getClass().getName());
            }
        }
    }
    
    /**
     * 创建默认 Agent
     *
     * @return 默认 Agent
     */
    private Agent createDefaultAgent() {
        AgentSpec defaultSpec = AgentSpec.builder()
                .name("jimi")
                .description("Jimi 默认助手，一个强大的 AI 编程助手")
                .version("2.0.0")
                .systemPrompt("你是 Jimi，一个强大的 AI 编程助手。你可以帮助用户完成各种编程任务，包括代码编写、调试、重构等。\n"
                        + "你有以下工具可用：\n"
                        + "- read_file: 读取文件内容\n"
                        + "- write_file: 写入文件内容\n"
                        + "- list_dir: 列出目录内容\n"
                        + "- bash: 执行 Shell 命令\n"
                        + "请始终使用清晰、专业的语言，并在执行危险操作前确认。")
                .build();
        
        return Agent.builder()
                .name(defaultSpec.getName())
                .description(defaultSpec.getDescription())
                .version(defaultSpec.getVersion())
                .systemPrompt(defaultSpec.getSystemPrompt())
                .tools(loadTools(defaultSpec))
                .maxSteps(100)
                .build();
    }
}
