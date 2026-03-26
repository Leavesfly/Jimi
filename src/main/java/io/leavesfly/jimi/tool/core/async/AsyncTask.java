package io.leavesfly.jimi.tool.core.async;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.core.agent.Agent;
import io.leavesfly.jimi.core.agent.AgentRegistry;
import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.core.agent.SubagentSpec;
import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.core.async.AsyncSubagentManager;
import io.leavesfly.jimi.core.async.AsyncSubagentMode;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.WireAware;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 异步任务工具 - 在后台启动子代理执行任务
 * <p>
 * 与同步的 Task 工具不同，AsyncTask 会立即返回，子代理在后台独立运行。
 * <p>
 * 核心特性：
 * 1. 后台执行：主代理可继续处理其他任务
 * 2. 生命周期独立：子代理可在主代理完成后继续运行
 * 3. 状态查询：通过 /async 命令查看执行状态
 * 4. 可取消：支持取消正在运行的子代理
 * <p>
 * 适用场景：
 * - 长时间构建任务（mvn clean install）
 * - 日志监控
 * - 并行任务处理
 * 
 * @author Jimi
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class AsyncTask extends AbstractTool<AsyncTask.Params> implements WireAware {
    
    /**
     * AsyncTask 工具参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {
        
        /**
         * 子代理名称
         */
        @JsonProperty("subagent_name")
        @JsonPropertyDescription("要使用的子代理名称，必须是可用子代理之一")
        private String subagentName;
        
        /**
         * 任务提示词
         */
        @JsonProperty("prompt")
        @JsonPropertyDescription("发送给子代理的任务提示，需包含完整的上下文信息")
        private String prompt;
        
        /**
         * 执行模式
         */
        @JsonProperty("mode")
        @JsonPropertyDescription("执行模式: fire_and_forget(后台运行，默认), watch(持续监控), wait_complete(等待完成)")
        private String mode;
        
        /**
         * 超时时间（秒）
         */
        @JsonProperty("timeout_seconds")
        @JsonPropertyDescription("超时时间（秒），可选。超时后子代理会被终止")
        private Integer timeoutSeconds;
        
        // ==================== Watch 模式参数 ====================
        
        /**
         * 监控目标（watch 模式使用）
         */
        @JsonProperty("watch_target")
        @JsonPropertyDescription("watch模式: 监控目标，如文件路径(/var/log/app.log)或命令(tail -f /var/log/app.log)")
        private String watchTarget;
        
        /**
         * 触发模式（正则表达式）
         */
        @JsonProperty("trigger_pattern")
        @JsonPropertyDescription("watch模式: 触发模式，正则表达式，匹配时通知用户（如 ERROR|FATAL|OutOfMemoryError）")
        private String triggerPattern;
        
        /**
         * 触发后执行的动作描述
         */
        @JsonProperty("on_trigger")
        @JsonPropertyDescription("watch模式: 触发后子代理应执行的动作描述（如: 分析错误原因并汇报）")
        private String onTrigger;
        
        /**
         * 触发后是否继续监控
         */
        @JsonProperty("continue_after_trigger")
        @JsonPropertyDescription("watch模式: 触发后是否继续监控，默认false（触发后停止）")
        private Boolean continueAfterTrigger;
    }
    
    private Wire parentWire;
    private JimiRuntime jimiRuntime;
    private AgentSpec agentSpec;
    private Map<String, SubagentSpec> subagentSpecs;
    private Map<String, Agent> loadedAgents;
    private volatile boolean subagentsLoaded = false;
    
    @Autowired
    private AsyncSubagentManager asyncSubagentManager;
    
    @Autowired
    private AgentRegistry agentRegistry;
    
    @Autowired
    public AsyncTask() {
        super("AsyncTask", buildDescription(), Params.class);
        this.loadedAgents = new HashMap<>();
    }
    
    private static String buildDescription() {
        return """
                启动异步子代理在后台执行任务。
                
                **与同步 Task 工具的区别**：
                - AsyncTask：立即返回，子代理后台运行
                - Task：等待子代理完成后返回结果
                
                **可用模式**：
                - `fire_and_forget`：后台运行（默认）- 启动后立即返回
                - `watch`：持续监控 - 监控日志/输出，匹配模式时触发
                - `wait_complete`：等待完成（建议使用 Task 工具）
                
                **Watch 模式示例**：
                监控日志文件，发现错误时通知：
                - watch_target: "/var/log/app.log"
                - trigger_pattern: "ERROR|FATAL|OutOfMemoryError"
                - on_trigger: "分析错误原因并通知用户"
                
                启动后可使用 `/async list` 查看状态，`/async cancel <id>` 取消执行。
                """;
    }
    
    @Override
    public void setWire(Wire wire) {
        this.parentWire = wire;
    }
    
    /**
     * 设置运行时参数
     */
    public void setRuntimeParams(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        this.agentSpec = agentSpec;
        this.jimiRuntime = jimiRuntime;
        this.subagentSpecs = agentSpec.getSubagents();
    }
    
    @Override
    public String getDescription() {
        // 动态生成描述，包含可用的子代理列表
        if (subagentSpecs == null || subagentSpecs.isEmpty()) {
            return super.getDescription();
        }
        
        StringBuilder sb = new StringBuilder(super.getDescription());
        sb.append("\n\n**可用的子代理：**\n");
        for (Map.Entry<String, SubagentSpec> entry : subagentSpecs.entrySet()) {
            sb.append("- `").append(entry.getKey()).append("`: ")
                    .append(entry.getValue().getDescription()).append("\n");
        }
        return sb.toString();
    }
    
    @Override
    public Mono<ToolResult> execute(Params params) {
        log.info("AsyncTask called: subagent={}, mode={}", 
                params.getSubagentName(), params.getMode());
        
        // 参数校验
        if (params == null) {
            return Mono.just(ToolResult.error("参数不能为空", "参数错误"));
        }
        if (params.getSubagentName() == null || params.getSubagentName().isBlank()) {
            return Mono.just(ToolResult.error("subagent_name 不能为空", "参数错误"));
        }
        if (params.getPrompt() == null || params.getPrompt().isBlank()) {
            return Mono.just(ToolResult.error("prompt 不能为空", "参数错误"));
        }
        
        // 懒加载子代理
        return ensureSubagentsLoaded()
                .then(Mono.defer(() -> {
                    // 检查子代理是否存在
                    Agent agent = loadedAgents.get(params.getSubagentName());
                    if (agent == null) {
                        return Mono.just(ToolResult.error(
                                "子代理不存在: " + params.getSubagentName() + 
                                "\n可用的子代理: " + String.join(", ", subagentSpecs.keySet()),
                                "子代理未找到"
                        ));
                    }
                    
                    // 解析模式
                    AsyncSubagentMode mode = AsyncSubagentMode.fromValue(params.getMode());
                    
                    return switch (mode) {
                        case FIRE_AND_FORGET -> executeFireAndForget(agent, params);
                        case WATCH -> executeWatch(agent, params);
                        case WAIT_COMPLETE -> executeWaitComplete(params);
                        default -> Mono.just(ToolResult.error("不支持的模式: " + mode, "参数错误"));
                    };
                }));
    }
    
    /**
     * Fire-and-Forget 模式：后台执行
     */
    private Mono<ToolResult> executeFireAndForget(Agent agent, Params params) {
        Duration timeout = params.getTimeoutSeconds() != null
                ? Duration.ofSeconds(params.getTimeoutSeconds())
                : null;
        
        return asyncSubagentManager.startAsync(
                agent,
                jimiRuntime,
                params.getPrompt(),
                parentWire,
                null,  // 无回调
                timeout
        ).map(subagentId -> {
            String output = String.format("""
                    异步子代理已启动
                    
                    - **ID**: %s
                    - **名称**: %s
                    - **模式**: 后台运行
                    %s
                    
                    **管理命令**：
                    - `/async list` - 查看所有活跃子代理
                    - `/async status %s` - 查看详细状态
                    - `/async cancel %s` - 取消执行
                    - `/async result %s` - 获取完成后的结果
                    """,
                    subagentId,
                    agent.getName(),
                    timeout != null ? "- **超时**: " + timeout.getSeconds() + "秒" : "",
                    subagentId,
                    subagentId,
                    subagentId
            );
            return ToolResult.ok(output, "异步子代理已启动: " + subagentId);
        });
    }
    
    /**
     * Wait-Complete 模式：提示使用 Task 工具
     */
    private Mono<ToolResult> executeWaitComplete(Params params) {
        return Mono.just(ToolResult.ok(
                "wait_complete 模式请使用 Task 工具（同步子代理）。\n" +
                "AsyncTask 专门用于后台异步执行场景。",
                "模式提示"
        ));
    }
    
    /**
     * Watch 模式：持续监控
     */
    private Mono<ToolResult> executeWatch(Agent agent, Params params) {
        // 参数校验
        if (params.getWatchTarget() == null || params.getWatchTarget().isBlank()) {
            return Mono.just(ToolResult.error(
                    "watch 模式必须指定 watch_target 参数",
                    "参数错误"
            ));
        }
        if (params.getTriggerPattern() == null || params.getTriggerPattern().isBlank()) {
            return Mono.just(ToolResult.error(
                    "watch 模式必须指定 trigger_pattern 参数",
                    "参数错误"
            ));
        }
        
        Duration timeout = params.getTimeoutSeconds() != null
                ? Duration.ofSeconds(params.getTimeoutSeconds())
                : null;
        
        boolean continueAfterTrigger = Boolean.TRUE.equals(params.getContinueAfterTrigger());
        
        return asyncSubagentManager.startWatcher(
                agent,
                jimiRuntime,
                params.getPrompt(),
                params.getWatchTarget(),
                params.getTriggerPattern(),
                params.getOnTrigger(),
                continueAfterTrigger,
                parentWire,
                timeout
        ).map(subagentId -> {
            String output = String.format("""
                    监控子代理已启动
                    
                    - **ID**: %s
                    - **名称**: %s
                    - **模式**: Watch (持续监控)
                    - **监控目标**: %s
                    - **触发模式**: %s
                    - **触发后继续**: %s
                    %s
                    
                    当匹配到触发模式时，会通知用户。
                    
                    **管理命令**：
                    - `/async list` - 查看所有活跃子代理
                    - `/async cancel %s` - 停止监控
                    """,
                    subagentId,
                    agent.getName(),
                    params.getWatchTarget(),
                    params.getTriggerPattern(),
                    continueAfterTrigger ? "是" : "否",
                    timeout != null ? "- **超时**: " + timeout.getSeconds() + "秒" : "",
                    subagentId
            );
            return ToolResult.ok(output, "监控子代理已启动: " + subagentId);
        });
    }
    
    /**
     * 懒加载所有子代理
     */
    private Mono<Void> ensureSubagentsLoaded() {
        if (!subagentsLoaded) {
            synchronized (this) {
                if (!subagentsLoaded) {
                    return loadSubagents()
                            .doOnSuccess(v -> subagentsLoaded = true);
                }
            }
        }
        return Mono.empty();
    }
    
    /**
     * 加载所有子代理
     */
    private Mono<Void> loadSubagents() {
        if (subagentSpecs == null || subagentSpecs.isEmpty()) {
            return Mono.empty();
        }
        
        return Flux.fromIterable(subagentSpecs.entrySet())
                .flatMap(entry -> {
                    String name = entry.getKey();
                    SubagentSpec spec = entry.getValue();
                    
                    log.debug("Loading subagent for AsyncTask: {}", name);
                    
                    return agentRegistry.loadSubagent(spec, jimiRuntime)
                            .doOnSuccess(agent -> {
                                if (agent != null) {
                                    loadedAgents.put(name, agent);
                                    log.info("Loaded subagent for AsyncTask: {} -> {}", name, agent.getName());
                                }
                            })
                            .doOnError(e -> log.error("Failed to load subagent: {}", name, e))
                            .onErrorResume(e -> Mono.empty());
                })
                .then();
    }
}
