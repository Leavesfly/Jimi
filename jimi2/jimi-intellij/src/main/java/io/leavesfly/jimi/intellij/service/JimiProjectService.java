package io.leavesfly.jimi.intellij.service;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import io.leavesfly.jimi.adk.api.agent.Agent;
import io.leavesfly.jimi.adk.api.context.Context;
import io.leavesfly.jimi.adk.api.engine.Engine;
import io.leavesfly.jimi.adk.api.engine.ExecutionResult;
import io.leavesfly.jimi.adk.api.engine.Runtime;
import io.leavesfly.jimi.adk.api.tool.ToolRegistry;
import io.leavesfly.jimi.adk.api.wire.Wire;
import io.leavesfly.jimi.adk.core.context.DefaultContext;
import io.leavesfly.jimi.adk.core.engine.DefaultEngine;
import io.leavesfly.jimi.adk.core.tool.DefaultToolRegistry;
import io.leavesfly.jimi.adk.core.wire.DefaultWire;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Jimi 项目服务
 * <p>
 * 为每个项目提供独立的 Jimi Agent 实例
 * </p>
 *
 * @author Jimi2 Team
 */
@Service(Service.Level.PROJECT)
public final class JimiProjectService {
    
    private static final Logger log = LoggerFactory.getLogger(JimiProjectService.class);
    
    /** 关联的项目 */
    private final Project project;
    
    /** 消息总线 */
    private final Wire wire;
    
    /** 对话上下文 */
    private Context context;
    
    /** 工具注册表 */
    private final ToolRegistry toolRegistry;
    
    /** 执行引擎 */
    private Engine engine;
    
    /** 当前 Agent */
    private Agent agent;
    
    /**
     * 构造函数（由 IntelliJ 平台调用）
     *
     * @param project 项目实例
     */
    public JimiProjectService(Project project) {
        this.project = project;
        this.wire = new DefaultWire();
        this.context = new DefaultContext();
        
        ObjectMapper objectMapper = new ObjectMapper();
        this.toolRegistry = new DefaultToolRegistry(objectMapper);
        
        initAgent();
        initEngine();
        
        log.info("Jimi 项目服务已初始化: {}", project.getName());
    }
    
    /**
     * 初始化 Agent
     */
    private void initAgent() {
        this.agent = Agent.builder()
                .name("jimi-intellij")
                .description("Jimi IntelliJ IDEA 助手")
                .version("2.0.0")
                .systemPrompt("你是 Jimi，一个集成在 IntelliJ IDEA 中的 AI 编程助手。你可以帮助用户：\n" +
                        "1. 生成和优化代码\n" +
                        "2. 解释代码逻辑\n" +
                        "3. 查找和修复 Bug\n" +
                        "4. 重构代码结构\n" +
                        "请简洁、专业地回答问题。")
                .maxSteps(50)
                .build();
    }
    
    /**
     * 初始化引擎
     */
    private void initEngine() {
        Path workDir = getProjectPath();
        
        Runtime runtime = Runtime.builder()
                .workDir(workDir)
                .build();
        
        this.engine = DefaultEngine.builder()
                .agent(agent)
                .runtime(runtime)
                .context(context)
                .toolRegistry(toolRegistry)
                .wire(wire)
                .build();
    }
    
    /**
     * 获取项目路径
     *
     * @return 项目路径
     */
    private Path getProjectPath() {
        String basePath = project.getBasePath();
        return basePath != null ? Paths.get(basePath) : Paths.get(System.getProperty("user.dir"));
    }
    
    /**
     * 发送消息
     *
     * @param message 消息内容
     * @return 执行结果
     */
    public Mono<ExecutionResult> sendMessage(String message) {
        return engine.run(message);
    }
    
    /**
     * 发送带上下文的消息
     *
     * @param message          消息内容
     * @param additionalContext 额外上下文
     * @return 执行结果
     */
    public Mono<ExecutionResult> sendMessage(String message, String additionalContext) {
        return engine.run(message, additionalContext);
    }
    
    /**
     * 获取消息总线
     *
     * @return 消息总线
     */
    public Wire getWire() {
        return wire;
    }
    
    /**
     * 获取上下文
     *
     * @return 上下文
     */
    public Context getContext() {
        return context;
    }
    
    /**
     * 重置对话
     */
    public void resetConversation() {
        this.context = new DefaultContext();
        initEngine();
        log.info("对话已重置");
    }
    
    /**
     * 检查引擎是否正在运行
     *
     * @return 是否正在运行
     */
    public boolean isRunning() {
        return engine.isRunning();
    }
    
    /**
     * 中断执行
     */
    public void interrupt() {
        engine.interrupt();
    }
    
    /**
     * 获取项目服务实例
     *
     * @param project 项目
     * @return 服务实例
     */
    public static JimiProjectService getInstance(Project project) {
        return project.getService(JimiProjectService.class);
    }
}
