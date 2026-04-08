package io.leavesfly.jimi.client;

import io.leavesfly.jimi.config.info.ShellUIConfig;
import io.leavesfly.jimi.config.info.ThemeConfig;
import io.leavesfly.jimi.core.JimiEngine;
import io.leavesfly.jimi.core.session.Session;
import io.leavesfly.jimi.core.session.SessionManager;
import io.leavesfly.jimi.llm.message.ContentPart;
import io.leavesfly.jimi.llm.message.TextPart;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.message.WireMessage;
import io.leavesfly.jimi.wire.message.request.ContextQueryRequest;
import io.leavesfly.jimi.wire.message.request.RunCommandRequest;
import io.leavesfly.jimi.wire.message.request.RuntimeInfoQueryRequest;
import io.leavesfly.jimi.wire.message.request.SessionResetRequest;
import io.leavesfly.jimi.wire.message.request.ToolExecuteRequest;
import io.leavesfly.jimi.wire.message.request.ThemeUpdateRequest;
import io.leavesfly.jimi.wire.message.request.ToolNamesQueryRequest;
import io.leavesfly.jimi.wire.message.request.ToolQueryRequest;
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
 * 纯Wire消息驱动：
 * - 所有运行时操作通过Wire请求-响应消息与Engine交互
 * - 不直接持有或调用JimiEngine实例
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

    // ==================== 会话管理 ====================
    private final SessionManager sessionManager;
    private String sessionId;

    /**
     * 构造函数 - 初始化阶段完成所有装配
     * <p>
     * 初始化时从 engine 获取所有配置并缓存，之后运行时仅通过 Wire 消息与 Engine 交互。
     * engine 参数仅在构造期间使用，不会被持有。
     *
     * @param engine         JimiEngine实例（仅用于初始化阶段获取配置，不会被持有）
     * @param sessionManager SessionManager实例
     */
    public WireEngineClient(JimiEngine engine, SessionManager sessionManager) {
        this.sessionManager = sessionManager;
        this.wire = engine.getWire();

        // 初始化时获取所有配置，后续不再访问engine
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

    // ==================== 运行时操作（通过Wire消息驱动） ====================

    @Override
    public Mono<Void> runCommand(String input) {
        return runCommand(List.of(TextPart.of(input)));
    }

    @Override
    public Mono<Void> runCommand(List<ContentPart> input) {
        return wire.request(new RunCommandRequest(input));
    }

    @Override
    public Mono<ToolResult> executeTool(String toolName, String arguments) {
        return wire.request(new ToolExecuteRequest(toolName, arguments));
    }

    @Override
    public boolean hasTool(String toolName) {
        return Boolean.TRUE.equals(wire.request(new ToolQueryRequest(toolName)).block());
    }

    // ==================== 运行时查询（通过Wire消息驱动） ====================

    @Override
    public int getTokenCount() {
        ContextQueryRequest.ContextInfo info = wire.request(new ContextQueryRequest()).block();
        return info != null ? info.getTokenCount() : 0;
    }

    @Override
    public int getHistorySize() {
        ContextQueryRequest.ContextInfo info = wire.request(new ContextQueryRequest()).block();
        return info != null ? info.getHistorySize() : 0;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<String> getToolNames() {
        List<String> names = wire.request(new ToolNamesQueryRequest()).block();
        return names != null ? names : List.of();
    }

    @Override
    public ContextQueryRequest.ContextInfo getContextInfo() {
        ContextQueryRequest.ContextInfo info = wire.request(new ContextQueryRequest()).block();
        return info != null ? info : ContextQueryRequest.ContextInfo.builder()
                .tokenCount(0).historySize(0).checkpointCount(0).build();
    }

    @Override
    public RuntimeInfoQueryRequest.RuntimeInfo getRuntimeInfo() {
        RuntimeInfoQueryRequest.RuntimeInfo info = wire.request(new RuntimeInfoQueryRequest()).block();
        return info != null ? info : RuntimeInfoQueryRequest.RuntimeInfo.builder()
                .llmConfigured(false).workDir("").sessionId("").historyFile("").yoloMode(false).build();
    }

    @Override
    public Mono<Void> resetContext() {
        return wire.request(new SessionResetRequest());
    }

    @Override
    public void updateTheme(String themeName, ThemeConfig themeConfig) {
        wire.request(new ThemeUpdateRequest(themeName, themeConfig)).block();
    }

    // ==================== 会话管理 ====================

    @Override
    public Mono<Void> newSession() {
        return Mono.defer(() -> {
            // 创建新会话
            Session newSession = sessionManager.createSession(workDir);
            this.sessionId = newSession.getId();

            // 通过Wire请求重置上下文
            return wire.request(new SessionResetRequest())
                    .doOnSuccess(v -> {
                        // 重置 Wire（创建新的 Sink）
                        wire.reset();
                        log.info("New session created: {}", sessionId);
                    });
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

}
