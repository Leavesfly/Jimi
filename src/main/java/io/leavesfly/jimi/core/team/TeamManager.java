package io.leavesfly.jimi.core.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.core.agent.Agent;
import io.leavesfly.jimi.core.agent.AgentRegistry;
import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.core.engine.context.ContextManager;
import io.leavesfly.jimi.tool.ToolRegistryFactory;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.message.SubagentStarting;
import io.leavesfly.jimi.wire.message.SubagentCompleted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队管理器 — Team Lead 的核心调度组件
 * <p>
 * 负责创建团队、分配任务、并发启动 Teammate、监控进度、汇总结果。
 * <p>
 * 生命周期：
 * 1. spawnTeam() — 创建 SharedTaskList、TeamMessageBus，加载 Teammate Agent
 * 2. 并发启动所有 TeammateRunner
 * 3. awaitCompletion() — 等待所有任务完成或超时
 * 4. aggregateResults() — 汇总结果
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class TeamManager {

    private final AgentRegistry agentRegistry;
    private final ObjectMapper objectMapper;
    private final ToolRegistryFactory toolRegistryFactory;
    private final ContextManager contextManager;
    private final ApplicationContext applicationContext;

    @Autowired
    private Wire wire;

    private JimiRuntime jimiRuntime;
    private String teamId;
    private SharedTaskList taskList;
    private TeamMessageBus messageBus;
    private final Map<String, TeammateRunner> teammates = new ConcurrentHashMap<>();
    private Instant startTime;

    @Autowired
    public TeamManager(AgentRegistry agentRegistry,
                       ObjectMapper objectMapper,
                       ToolRegistryFactory toolRegistryFactory,
                       ContextManager contextManager,
                       ApplicationContext applicationContext) {
        this.agentRegistry = agentRegistry;
        this.objectMapper = objectMapper;
        this.toolRegistryFactory = toolRegistryFactory;
        this.contextManager = contextManager;
        this.applicationContext = applicationContext;
    }

    /**
     * 设置运行时参数
     */
    public void setRuntimeParams(JimiRuntime jimiRuntime) {
        this.jimiRuntime = jimiRuntime;
    }

    /**
     * 创建团队并执行所有任务
     *
     * @param teamSpec 团队配置
     * @param tasks    动态任务列表（来自 TeamAgentTool 的参数）
     * @return 团队执行结果
     */
    public Mono<TeamResult> executeTeam(TeamSpec teamSpec, List<TeamTaskSpec> tasks) {
        this.teamId = teamSpec.getName() != null ? teamSpec.getName() : "team-" + UUID.randomUUID().toString().substring(0, 8);
        this.startTime = Instant.now();
        this.taskList = new SharedTaskList(teamId);
        this.messageBus = applicationContext.getBean(TeamMessageBus.class);
        this.messageBus.init(teamId);

        log.info("[Team {}] Starting team execution with {} teammates and {} tasks",
                teamId, teamSpec.getTeammates().size(), tasks.size());

        if (wire != null) {
            wire.send(new SubagentStarting(teamId, "Team execution started"));
        }

        // 1. 添加任务到 SharedTaskList
        for (TeamTaskSpec taskSpec : tasks) {
            taskList.addTask(taskSpec.getDescription(), taskSpec.getPriority(), taskSpec.getDependencies());
        }

        // 2. 加载所有 Teammate Agent 并启动
        Duration timeout = Duration.ofSeconds(teamSpec.getTimeoutSeconds());
        int maxConcurrency = Math.min(teamSpec.getMaxConcurrency(), teamSpec.getTeammates().size());

        return loadAndStartTeammates(teamSpec.getTeammates(), maxConcurrency)
                .timeout(timeout)
                .then(Mono.defer(this::buildResult))
                .doOnSuccess(result -> {
                    log.info("[Team {}] Execution completed: {}", teamId, result.isSuccess() ? "SUCCESS" : "WITH ERRORS");
                    if (wire != null) {
                        wire.send(new SubagentCompleted(result.toSummary()));
                    }
                    messageBus.close();
                })
                .doOnError(error -> {
                    log.error("[Team {}] Execution failed: {}", teamId, error.getMessage());
                    if (wire != null) {
                        wire.send(new SubagentCompleted("Team execution failed: " + error.getMessage()));
                    }
                    messageBus.close();
                })
                .onErrorResume(error -> {
                    TeamResult errorResult = TeamResult.builder()
                            .teamId(teamId)
                            .success(false)
                            .startTime(startTime)
                            .endTime(Instant.now())
                            .errors(List.of("Team execution error: " + error.getMessage()))
                            .build();
                    return Mono.just(errorResult);
                });
    }

    /**
     * 加载所有 Teammate 的 Agent 并并发启动执行
     */
    private Mono<Void> loadAndStartTeammates(List<TeammateSpec> teammateSpecs, int maxConcurrency) {
        return Flux.fromIterable(teammateSpecs)
                .flatMap(spec -> loadTeammate(spec)
                                .doOnError(e -> log.error("[Team {}] Failed to load teammate {}: {}",
                                        teamId, spec.getTeammateId(), e.getMessage()))
                                .onErrorResume(e -> Mono.empty()),
                        maxConcurrency)
                .then();
    }

    /**
     * 加载单个 Teammate 并启动执行循环
     */
    private Mono<Void> loadTeammate(TeammateSpec spec) {
        return Mono.defer(() -> {
            Mono<Agent> agentMono = agentRegistry.loadAgent(spec.getAgentPath(), jimiRuntime);
            Mono<AgentSpec> agentSpecMono = agentRegistry.loadAgentSpec(spec.getAgentPath());

            return Mono.zip(agentMono, agentSpecMono)
                    .flatMap(tuple -> {
                        Agent agent = tuple.getT1();
                        AgentSpec agentSpec = tuple.getT2();

                        TeammateRunner runner = applicationContext.getBean(TeammateRunner.class);
                        runner.setRuntimeParams(
                                spec.getTeammateId(),
                                agent,
                                agentSpec,
                                jimiRuntime,
                                taskList,
                                messageBus);

                        teammates.put(spec.getTeammateId(), runner);
                        log.info("[Team {}] Teammate '{}' loaded, starting execution", teamId, spec.getTeammateId());

                        return runner.run()
                                .subscribeOn(Schedulers.boundedElastic());
                    });
        });
    }

    /**
     * 汇总所有任务结果
     */
    private Mono<TeamResult> buildResult() {
        List<TeamResult.TaskResult> taskResults = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean allSuccess = true;

        for (TeamTask task : taskList.getAllTasks()) {
            boolean taskSuccess = task.getStatus() == TaskStatus.COMPLETED;
            if (!taskSuccess) {
                allSuccess = false;
            }

            taskResults.add(TeamResult.TaskResult.builder()
                    .taskId(task.getTaskId())
                    .description(task.getDescription())
                    .executedBy(task.getClaimedBy() != null ? task.getClaimedBy() : "unassigned")
                    .success(taskSuccess)
                    .result(task.getResult())
                    .build());

            if (task.getStatus() == TaskStatus.FAILED) {
                errors.add(String.format("Task %s failed: %s", task.getTaskId(), task.getResult()));
            } else if (!task.isTerminal()) {
                errors.add(String.format("Task %s not completed (status: %s)", task.getTaskId(), task.getStatus()));
            }
        }

        TeamResult result = TeamResult.builder()
                .teamId(teamId)
                .success(allSuccess)
                .startTime(startTime)
                .endTime(Instant.now())
                .taskResults(taskResults)
                .errors(errors)
                .build();

        return Mono.just(result);
    }

    /**
     * 取消团队执行
     */
    public void cancelTeam() {
        log.info("[Team {}] Cancelling team execution", teamId);
        jimiRuntime.getSession().cancel();
        messageBus.close();
    }
}
