package io.leavesfly.jimi.wire;

import io.leavesfly.jimi.config.info.ShellUIConfig;
import io.leavesfly.jimi.core.engine.AgentExecutor;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.wire.message.WireRequest;
import io.leavesfly.jimi.wire.message.request.ContextQueryRequest;
import io.leavesfly.jimi.wire.message.request.RunCommandRequest;
import io.leavesfly.jimi.wire.message.request.RuntimeInfoQueryRequest;
import io.leavesfly.jimi.wire.message.request.SessionResetRequest;
import io.leavesfly.jimi.wire.message.request.ThemeUpdateRequest;
import io.leavesfly.jimi.wire.message.request.ToolExecuteRequest;
import io.leavesfly.jimi.wire.message.request.ToolNamesQueryRequest;
import io.leavesfly.jimi.wire.message.request.ToolQueryRequest;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

import java.util.ArrayList;

/**
 * Wire 请求处理器
 * <p>
 * 在 Engine 侧订阅 Wire 上行请求流，根据请求类型分发到对应的处理逻辑，
 * 处理完成后通过 WireRequest 内置的 Sink 回写响应。
 * <p>
 * 生命周期由 JimiEngine 管理，在 Engine 创建时注册，reset 时重新订阅。
 */
@Slf4j
public class WireRequestHandler {

    private final Wire wire;
    private final AgentExecutor executor;
    private Disposable subscription;

    public WireRequestHandler(Wire wire, AgentExecutor executor) {
        this.wire = wire;
        this.executor = executor;
    }

    /**
     * 启动请求监听
     */
    public void start() {
        stop();
        this.subscription = wire.requests().subscribe(
                this::handleRequest,
                error -> log.error("Wire request handler error", error)
        );
        log.info("WireRequestHandler started");
    }

    /**
     * 停止请求监听
     */
    public void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    private void handleRequest(WireRequest<?> request) {
        String messageType = request.getMessageType();
        log.debug("Handling wire request: {}", messageType);

        try {
            switch (messageType) {
                case "request.run_command":
                    handleRunCommand((RunCommandRequest) request);
                    break;
                case "request.tool_execute":
                    handleToolExecute((ToolExecuteRequest) request);
                    break;
                case "request.tool_query":
                    handleToolQuery((ToolQueryRequest) request);
                    break;
                case "request.context_query":
                    handleContextQuery((ContextQueryRequest) request);
                    break;
                case "request.tool_names_query":
                    handleToolNamesQuery((ToolNamesQueryRequest) request);
                    break;
                case "request.runtime_info_query":
                    handleRuntimeInfoQuery((RuntimeInfoQueryRequest) request);
                    break;
                case "request.theme_update":
                    handleThemeUpdate((ThemeUpdateRequest) request);
                    break;
                case "request.session_reset":
                    handleSessionReset((SessionResetRequest) request);
                    break;
                default:
                    log.warn("Unknown wire request type: {}", messageType);
                    request.fail(new UnsupportedOperationException("Unknown request type: " + messageType));
            }
        } catch (Exception e) {
            log.error("Failed to handle wire request: {}", messageType, e);
            request.fail(e);
        }
    }

    private void handleRunCommand(RunCommandRequest request) {
        executor.execute(request.getInput(), false)
                .doOnSuccess(v -> request.completeEmpty())
                .doOnError(request::fail)
                .subscribe();
    }

    private void handleToolExecute(ToolExecuteRequest request) {
        ToolRegistry toolRegistry = executor.getToolRegistry();
        toolRegistry.execute(request.getToolName(), request.getArguments())
                .doOnSuccess(request::complete)
                .doOnError(request::fail)
                .subscribe();
    }

    private void handleToolQuery(ToolQueryRequest request) {
        boolean hasTool = executor.getToolRegistry().hasTool(request.getToolName());
        request.complete(hasTool);
    }

    private void handleContextQuery(ContextQueryRequest request) {
        Context context = executor.getContext();
        ContextQueryRequest.ContextInfo info = ContextQueryRequest.ContextInfo.builder()
                .tokenCount(context.getTokenCount())
                .historySize(context.getHistory().size())
                .checkpointCount(context.getnCheckpoints())
                .build();
        request.complete(info);
    }

    private void handleRuntimeInfoQuery(RuntimeInfoQueryRequest request) {
        var runtime = executor.getRuntime();
        RuntimeInfoQueryRequest.RuntimeInfo info = RuntimeInfoQueryRequest.RuntimeInfo.builder()
                .llmConfigured(runtime.getLlm() != null)
                .workDir(runtime.getBuiltinArgs() != null
                        ? runtime.getBuiltinArgs().getJimiWorkDir().toString() : "")
                .sessionId(runtime.getSession() != null ? runtime.getSession().getId() : "")
                .historyFile(runtime.getSession() != null && runtime.getSession().getHistoryFile() != null
                        ? runtime.getSession().getHistoryFile().toString() : "")
                .yoloMode(runtime.isYoloMode())
                .build();
        request.complete(info);
    }

    private void handleToolNamesQuery(ToolNamesQueryRequest request) {
        request.complete(new ArrayList<>(executor.getToolRegistry().getToolNames()));
    }

    private void handleThemeUpdate(ThemeUpdateRequest request) {
        ShellUIConfig shellUI = executor.getRuntime().getConfig().getShellUI();
        if (shellUI != null) {
            shellUI.setThemeName(request.getThemeName());
            shellUI.setTheme(request.getThemeConfig());
        }
        request.completeEmpty();
    }

    private void handleSessionReset(SessionResetRequest request) {
        executor.getContext().revertTo(0)
                .doOnSuccess(v -> request.completeEmpty())
                .doOnError(request::fail)
                .subscribe();
    }
}
