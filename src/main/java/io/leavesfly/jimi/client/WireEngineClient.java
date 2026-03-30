package io.leavesfly.jimi.client;

import io.leavesfly.jimi.config.info.ShellUIConfig;
import io.leavesfly.jimi.config.info.ThemeConfig;
import io.leavesfly.jimi.core.JimiEngine;
import io.leavesfly.jimi.core.hook.HookContext;
import io.leavesfly.jimi.core.hook.HookRegistry;
import io.leavesfly.jimi.core.hook.HookType;
import io.leavesfly.jimi.core.session.Session;
import io.leavesfly.jimi.core.session.SessionManager;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.message.WireMessage;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;

/**
 * WireEngineClient 实现
 * <p>
 * 基于Wire消息总线实现EngineClient接口，遵循初始化态与运行态分离原则：
 * - 构造时完成所有配置的获取和缓存（初始化阶段）
 * - 运行时通过Wire消息与引擎交互（运行阶段）
 * <p>
 * 过渡期设计：
 * - 内部委托给JimiEngine实现功能
 * - 后续可改为纯Wire消息驱动
 */
@Slf4j
public class WireEngineClient implements EngineClient {

    // ==================== 初始化时注入的依赖（不可变） ====================
    private final Wire wire;
    private final String agentName;
    private final String modelName;
    private final Path workDir;
    private final ShellUIConfig shellUIConfig;
    private final ThemeConfig themeConfig;
    private final boolean yoloMode;

    // ==================== 内部委托（过渡期使用） ====================
    private final JimiEngine engine;
    private final HookRegistry hookRegistry;
    private final SessionManager sessionManager;

    // ==================== 会话状态 ====================
    private String sessionId;

    /**
     * 构造函数 - 初始化阶段完成所有装配
     * <p>
     * 在初始化阶段获取所有配置并缓存，运行阶段不再访问engine获取配置
     *
     * @param engine         JimiEngine实例
     * @param hookRegistry   HookRegistry实例
     * @param sessionManager SessionManager实例
     */
    public WireEngineClient(JimiEngine engine, HookRegistry hookRegistry, SessionManager sessionManager) {
        this.engine = engine;
        this.hookRegistry = hookRegistry;
        this.sessionManager = sessionManager;
        this.wire = engine.getWire();

        // 初始化时获取所有配置，后续不再访问engine获取这些配置
        this.agentName = engine.getAgent() != null ? engine.getAgent().getName() : "default";
        this.modelName = engine.getModel();
        this.workDir = engine.getRuntime().getWorkDir();
        this.shellUIConfig = engine.getRuntime().getConfig().getShellUI();
        this.themeConfig = resolveTheme(shellUIConfig);
        this.yoloMode = engine.getRuntime().isYoloMode();
        this.sessionId = engine.getRuntime().getSessionId();

        log.info("WireEngineClient initialized: agent={}, model={}, workDir={}, sessionId={}",
                agentName, modelName, workDir, sessionId);
    }

    /**
     * 解析主题配置
     */
    private ThemeConfig resolveTheme(ShellUIConfig uiConfig) {
        if (uiConfig == null) {
            return ThemeConfig.defaultTheme();
        }

        // 如果有自定义主题配置，直接使用
        if (uiConfig.getTheme() != null) {
            return uiConfig.getTheme();
        }

        // 否则根据主题名称获取预设主题
        return ThemeConfig.getPresetTheme(uiConfig.getThemeName());
    }

    // ==================== 初始化时获取的配置（直接返回，无需消息交互） ====================

    @Override
    public String getAgentName() {
        return agentName;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public Path getWorkDir() {
        return workDir;
    }

    @Override
    public ShellUIConfig getShellUIConfig() {
        return shellUIConfig;
    }

    @Override
    public ThemeConfig getThemeConfig() {
        return themeConfig;
    }

    @Override
    public boolean isYoloMode() {
        return yoloMode;
    }

    // ==================== 运行时操作（过渡期直接委托） ====================

    @Override
    public Mono<Void> runCommand(String input) {
        return engine.run(input);
    }

    @Override
    public Mono<Void> runCommand(List<ContentPart> input) {
        return engine.run(input);
    }

    @Override
    public Mono<ToolResult> executeTool(String toolName, String arguments) {
        return engine.getToolRegistry().execute(toolName, arguments);
    }

    @Override
    public boolean hasTool(String toolName) {
        return engine.getToolRegistry().hasTool(toolName);
    }

    @Override
    public Mono<Void> triggerHook(HookType type, HookContext context) {
        return hookRegistry.trigger(type, context);
    }

    // ==================== 运行时查询（过渡期直接委托） ====================

    @Override
    public int getTokenCount() {
        return engine.getContext().getTokenCount();
    }

    @Override
    public int getHistorySize() {
        return engine.getContext().getHistory().size();
    }

    // ==================== 会话管理 ====================

    @Override
    public Mono<Void> newSession() {
        return Mono.fromRunnable(() -> {
            // 创建新会话
            Session newSession = sessionManager.createSession(workDir);
            this.sessionId = newSession.getId();

            // 重置上下文（清空历史）
            engine.getContext().revertTo(0).block();

            // 重置 Wire
            wire.reset();

            log.info("New session created: {}", sessionId);
        });
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    // ==================== Wire订阅 ====================

    @Override
    public Flux<WireMessage> subscribe() {
        return wire.asFlux();
    }

    // ==================== 过渡期兼容方法（后续移除） ====================

    @Override
    @Deprecated
    public JimiEngine getUnderlyingEngine() {
        return engine;
    }
}
