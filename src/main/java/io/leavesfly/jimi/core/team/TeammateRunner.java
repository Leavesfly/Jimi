package io.leavesfly.jimi.core.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.core.JimiEngine;
import io.leavesfly.jimi.core.agent.Agent;
import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.core.compaction.SimpleCompaction;
import io.leavesfly.jimi.core.engine.AgentExecutor;
import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.core.engine.context.Context;
import io.leavesfly.jimi.core.engine.context.ContextManager;
import io.leavesfly.jimi.llm.message.Message;
import io.leavesfly.jimi.llm.message.MessageRole;
import io.leavesfly.jimi.tool.ToolRegistry;
import io.leavesfly.jimi.tool.ToolRegistryFactory;
import io.leavesfly.jimi.wire.Wire;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Teammate 执行器
 * <p>
 * 封装单个 Teammate 的执行循环：
 * 认领任务 → 执行 → 检查消息 → 汇报 → 认领下一个
 * <p>
 * 每个 TeammateRunner 拥有独立的上下文，但共享 SharedTaskList 和 TeamMessageBus。
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class TeammateRunner {

    @Getter
    private String teammateId;
    private Agent agent;
    private AgentSpec agentSpec;
    private JimiRuntime jimiRuntime;
    private SharedTaskList taskList;
    private TeamMessageBus messageBus;

    @Autowired
    private Wire wire;

    private final ObjectMapper objectMapper;
    private final ToolRegistryFactory toolRegistryFactory;
    private final ContextManager contextManager;

    private static final int MAX_IDLE_RETRIES = 20;
    private static final Duration IDLE_WAIT = Duration.ofMillis(500);

    @Autowired
    public TeammateRunner(ObjectMapper objectMapper,
                          ToolRegistryFactory toolRegistryFactory,
                          ContextManager contextManager) {
        this.objectMapper = objectMapper;
        this.toolRegistryFactory = toolRegistryFactory;
        this.contextManager = contextManager;
    }

    /**
     * 设置运行时参数
     */
    public void setRuntimeParams(String teammateId,
                                 Agent agent,
                                 AgentSpec agentSpec,
                                 JimiRuntime jimiRuntime,
                                 SharedTaskList taskList,
                                 TeamMessageBus messageBus) {
        this.teammateId = teammateId;
        this.agent = agent;
        this.agentSpec = agentSpec;
        this.jimiRuntime = jimiRuntime;
        this.taskList = taskList;
        this.messageBus = messageBus;
    }

    /**
     * 执行循环
     * <p>
     * 不断从 SharedTaskList 认领任务并执行，直到所有任务完成或无法继续。
     */
    public Mono<Void> run() {
        return loop(0);
    }

    private Mono<Void> loop(int idleCount) {
        return Mono.defer(() -> {
            if (jimiRuntime.getSession().isCancelled()) {
                log.info("[Teammate {}] Session cancelled, stopping", teammateId);
                return Mono.empty();
            }

            if (taskList.isAllCompleted()) {
                log.info("[Teammate {}] All tasks completed, stopping", teammateId);
                return Mono.empty();
            }

            Optional<TeamTask> claimedTask = taskList.claimTask(teammateId);
            if (claimedTask.isEmpty()) {
                if (!taskList.hasPendingWork()) {
                    log.info("[Teammate {}] No more pending work, stopping", teammateId);
                    return Mono.empty();
                }
                if (idleCount >= MAX_IDLE_RETRIES) {
                    log.warn("[Teammate {}] Max idle retries reached, stopping", teammateId);
                    return Mono.empty();
                }
                log.debug("[Teammate {}] No claimable tasks, waiting... (idle: {})", teammateId, idleCount);
                return Mono.delay(IDLE_WAIT).then(loop(idleCount + 1));
            }

            TeamTask task = claimedTask.get();
            return executeTask(task)
                    .then(Mono.defer(() -> loop(0)));
        });
    }

    /**
     * 执行单个任务
     */
    private Mono<Void> executeTask(TeamTask task) {
        task.markInProgress();
        log.info("[Teammate {}] Executing task: {} - {}", teammateId, task.getTaskId(), task.getDescription());

        return Mono.defer(() -> {
            Path historyFile = null;
            try {
                historyFile = createTempHistoryFile();
                Context subContext = new Context(historyFile, objectMapper);
                ToolRegistry subToolRegistry = toolRegistryFactory.create(
                        jimiRuntime.getBuiltinArgs(), jimiRuntime.getApproval(),
                        agentSpec, jimiRuntime, null);

                AgentExecutor executor = AgentExecutor.builder()
                        .agent(agent)
                        .runtime(jimiRuntime)
                        .context(subContext)
                        .wire(wire)
                        .toolRegistry(subToolRegistry)
                        .compaction(new SimpleCompaction())
                        .contextManager(contextManager)
                        .isSubagent(true)
                        .build();

                JimiEngine subEngine = JimiEngine.create(executor);

                String prompt = buildTaskPrompt(task);
                Path finalHistoryFile = historyFile;

                return subEngine.run(prompt)
                        .then(Mono.<Void>defer(() -> {
                            String response = extractFinalResponse(subContext);
                            task.markCompleted(response);

                            if (messageBus != null) {
                                messageBus.broadcast(teammateId,
                                        String.format("Task [%s] completed: %s",
                                                task.getTaskId(), truncate(response, 200)));
                            }

                            log.info("[Teammate {}] Task {} completed", teammateId, task.getTaskId());
                            return Mono.empty();
                        }))
                        .onErrorResume(error -> {
                            log.error("[Teammate {}] Task {} failed: {}", teammateId, task.getTaskId(), error.getMessage());
                            task.markFailed(error.getMessage());
                            return Mono.empty();
                        })
                        .doFinally(signal -> cleanupTempHistoryFile(finalHistoryFile));

            } catch (Exception e) {
                log.error("[Teammate {}] Failed to initialize task execution: {}", teammateId, e.getMessage());
                task.markFailed("Initialization error: " + e.getMessage());
                if (historyFile != null) {
                    cleanupTempHistoryFile(historyFile);
                }
                return Mono.<Void>empty();
            }
        });
    }

    /**
     * 构建任务提示词，包含团队上下文信息
     */
    private String buildTaskPrompt(TeamTask task) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("## Task Assignment\n\n");
        prompt.append("You are **").append(teammateId).append("**, a teammate in a collaborative team.\n\n");
        prompt.append("### Your Task\n");
        prompt.append("**Task ID**: ").append(task.getTaskId()).append("\n");
        prompt.append("**Description**: ").append(task.getDescription()).append("\n\n");

        List<TeamMessage> recentMessages = messageBus != null
                ? messageBus.getRecentMessages(teammateId, 10)
                : List.of();
        if (!recentMessages.isEmpty()) {
            prompt.append("### Team Messages\n");
            prompt.append("Recent messages from your teammates:\n\n");
            for (TeamMessage message : recentMessages) {
                prompt.append("- **").append(message.getFromTeammateId()).append("**: ")
                        .append(message.getContent()).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("### Team Progress\n");
        prompt.append(taskList.getProgressSummary()).append("\n\n");

        prompt.append("### Instructions\n");
        prompt.append("Complete the task described above. Be thorough and provide detailed results.\n");

        return prompt.toString();
    }

    /**
     * 从子 Agent 上下文中提取最终响应
     */
    private String extractFinalResponse(Context context) {
        List<Message> history = context.getHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (message.getRole() == MessageRole.ASSISTANT) {
                String text = message.getTextContent();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return "Task completed (no text response)";
    }

    private Path createTempHistoryFile() throws IOException {
        return Files.createTempFile("jimi-team-" + teammateId + "-", ".jsonl");
    }

    private void cleanupTempHistoryFile(Path historyFile) {
        try {
            if (historyFile != null) {
                Files.deleteIfExists(historyFile);
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup temp history file: {}", historyFile, e);
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
