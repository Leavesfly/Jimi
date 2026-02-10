package io.leavesfly.jimi.intellij.service;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.llm.LLM;
import io.leavesfly.jimi.adk.api.tool.Tool;
import io.leavesfly.jimi.adk.api.tool.ToolProvider;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.engine.RuntimeConfig;
import io.leavesfly.jimi.adk.api.agent.AgentSpec;
import io.leavesfly.jimi.adk.core.JimiRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Jimi 项目服务
 * <p>
 * 为每个项目提供独立的 Jimi Agent 实例。
 * 使用 {@link JimiRuntime} 统一组装核心组件。
 * </p>
 *
 * @author Jimi2 Team
 */
@Service(Service.Level.PROJECT)
public final class JimiProjectService {
    
    private static final Logger log = LoggerFactory.getLogger(JimiProjectService.class);
    
    /** 关联的项目 */
    private final Project project;
    
    /** JimiRuntime 统一运行时 */
    private JimiRuntime jimiRuntime;

    /**
     * 构造函数（由 IntelliJ 平台调用）
     *
     * @param project 项目实例
     */
    @SuppressWarnings("unchecked")
    public JimiProjectService(Project project) {
        this.project = project;

        Agent agent = buildAgent();
        initRuntime(agent);
        
        log.info("Jimi 项目服务已初始化: {}", project.getName());
    }
    
    /**
     * 构建 Agent
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Agent buildAgent() {
        // 通过 SPI 发现工具
        AgentSpec agentSpec = AgentSpec.builder()
                .name("jimi-intellij")
                .description("Jimi IntelliJ IDEA 助手")
                .version("2.0.0")
                .build();

        RuntimeConfig toolConfig = RuntimeConfig.builder().workDir(getProjectPath()).build();
        Runtime toolRuntime = Runtime.builder().config(toolConfig).build();

        List<Tool> spiTools = new ArrayList<>();
        for (ToolProvider provider : ServiceLoader.load(ToolProvider.class)) {
            if (provider.supports(agentSpec, toolRuntime)) {
                for (Tool<?> t : provider.createTools(agentSpec, toolRuntime)) {
                    spiTools.add((Tool) t);
                }
            }
        }

        log.info("SPI 发现工具: {} 个", spiTools.size());

        return Agent.builder()
                .name("jimi-intellij")
                .description("Jimi IntelliJ IDEA 助手")
                .version("2.0.0")
                .systemPrompt("你是 Jimi，一个集成在 IntelliJ IDEA 中的 AI 编程助手。你可以帮助用户：\n" +
                        "1. 生成和优化代码\n" +
                        "2. 解释代码逻辑\n" +
                        "3. 查找和修复 Bug\n" +
                        "4. 重构代码结构\n" +
                        "请简洁、专业地回答问题。")
                .tools(new ArrayList<>(spiTools))
                .maxSteps(50)
                .build();
    }

    /**
     * 通过 JimiRuntime 统一初始化所有核心组件
     */
    private void initRuntime(Agent agent) {
        Path workDir = getProjectPath();

        // 从应用级服务获取 LLM
        LLM llm = JimiApplicationService.getInstance().getOrCreateLLM();
        if (llm == null) {
            log.warn("LLM 未初始化（API Key 未配置），引擎将无法正常工作");
        }

        this.jimiRuntime = JimiRuntime.builder()
                .agent(agent)
                .llm(llm)
                .workDir(workDir)
                .build();
    }
    
    /**
     * 获取项目路径
     */
    private Path getProjectPath() {
        String basePath = project.getBasePath();
        return basePath != null ? Paths.get(basePath) : Paths.get(System.getProperty("user.dir"));
    }
    
    /**
     * 发送消息
     */
    public Mono<ExecutionResult> sendMessage(String message) {
        return jimiRuntime.getEngine().run(message);
    }
    
    /**
     * 发送带上下文的消息
     */
    public Mono<ExecutionResult> sendMessage(String message, String additionalContext) {
        return jimiRuntime.getEngine().run(message, additionalContext);
    }
    
    /**
     * 获取消息总线
     */
    public Wire getWire() {
        return jimiRuntime.getWire();
    }
    
    /**
     * 获取上下文
     */
    public Context getContext() {
        return jimiRuntime.getContext();
    }
    
    /**
     * 重置对话
     */
    public void resetConversation() {
        Agent agent = buildAgent();
        initRuntime(agent);
        log.info("对话已重置");
    }
    
    /**
     * 检查引擎是否正在运行
     */
    public boolean isRunning() {
        return jimiRuntime.getEngine().isRunning();
    }
    
    /**
     * 中断执行
     */
    public void interrupt() {
        jimiRuntime.getEngine().interrupt();
    }
    
    /**
     * 获取项目服务实例
     */
    public static JimiProjectService getInstance(Project project) {
        return project.getService(JimiProjectService.class);
    }
}
