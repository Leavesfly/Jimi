package io.leavesfly.jimi.tool.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.leavesfly.jimi.core.agent.AgentSpec;
import io.leavesfly.jimi.core.engine.JimiRuntime;
import io.leavesfly.jimi.team.TeamManager;
import io.leavesfly.jimi.team.TeamSpec;
import io.leavesfly.jimi.team.TeamTaskSpec;
import io.leavesfly.jimi.team.TeammateSpec;
import io.leavesfly.jimi.tool.AbstractTool;
import io.leavesfly.jimi.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * TeamAgentTool — 团队协作工具
 * <p>
 * 允许 Main Agent 启动一个 Agent 团队来协作完成复杂任务。
 * 团队成员可以并行工作、共享任务列表、相互通信。
 * <p>
 * 与 SubAgentTool 的区别：
 * - SubAgentTool：独立的子 Agent，无横向通信
 * - TeamAgentTool：协作的团队，共享任务列表，可相互通信
 */
@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class TeamAgentTool extends AbstractTool<TeamAgentTool.Params> {

    private JimiRuntime jimiRuntime;
    private AgentSpec agentSpec;
    private TeamSpec teamSpec;

    private final ApplicationContext applicationContext;

    @Autowired
    public TeamAgentTool(ApplicationContext applicationContext) {
        super("TeamAgentTool",
                "启动一个 Agent 团队来协作完成复杂任务（description will be set when initialized）",
                Params.class);
        this.applicationContext = applicationContext;
    }

    /**
     * 设置运行时参数
     */
    public void setRuntimeParams(AgentSpec agentSpec, JimiRuntime jimiRuntime) {
        this.agentSpec = agentSpec;
        this.jimiRuntime = jimiRuntime;
        this.teamSpec = agentSpec.getTeam();
    }

    @Override
    public String getDescription() {
        if (teamSpec == null || teamSpec.getTeammates().isEmpty()) {
            return super.getDescription();
        }
        return buildDescription();
    }

    private String buildDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("启动一个 Agent 团队来协作完成复杂任务。\n\n");
        sb.append("团队成员可以并行工作、共享任务列表、相互通信。\n");
        sb.append("适用于有依赖关系或需要协调的复杂多步骤任务。\n\n");

        sb.append("**可用的团队成员：**\n\n");
        for (TeammateSpec teammate : teamSpec.getTeammates()) {
            sb.append("- `").append(teammate.getTeammateId()).append("`");
            if (teammate.getDescription() != null) {
                sb.append(": ").append(teammate.getDescription());
            }
            if (!teammate.getSpecialties().isEmpty()) {
                sb.append(" (擅长: ").append(String.join(", ", teammate.getSpecialties())).append(")");
            }
            sb.append("\n");
        }

        sb.append("\n**调度策略**: ").append(teamSpec.getStrategy().getValue()).append("\n");
        sb.append("**最大并发**: ").append(teamSpec.getMaxConcurrency()).append("\n");

        return sb.toString();
    }

    @Override
    public Mono<ToolResult> execute(Params params) {
        log.info("TeamAgentTool called with {} tasks", params.getTasks() != null ? params.getTasks().size() : 0);

        if (params == null || params.getTasks() == null || params.getTasks().isEmpty()) {
            return Mono.just(ToolResult.error("At least one task is required", "Invalid parameters"));
        }

        if (teamSpec == null || teamSpec.getTeammates().isEmpty()) {
            return Mono.just(ToolResult.error("No team configuration found. Please configure 'team' in agent.yaml", "No team config"));
        }

        // 构建任务规范列表
        List<TeamTaskSpec> taskSpecs = new ArrayList<>();
        for (int i = 0; i < params.getTasks().size(); i++) {
            TaskParam taskParam = params.getTasks().get(i);
            taskSpecs.add(TeamTaskSpec.builder()
                    .description(taskParam.getDescription())
                    .priority(taskParam.getPriority() > 0 ? taskParam.getPriority() : (i + 1))
                    .dependencies(taskParam.getDependencies() != null ? taskParam.getDependencies() : List.of())
                    .preferredTeammate(taskParam.getPreferredTeammate())
                    .build());
        }

        // 通过 Spring 容器获取 TeamManager 原型实例
        TeamManager teamManager = applicationContext.getBean(TeamManager.class);
        teamManager.setRuntimeParams(jimiRuntime);

        return teamManager.executeTeam(teamSpec, taskSpecs)
                .map(result -> {
                    if (result.isSuccess()) {
                        return ToolResult.ok(result.toSummary(), "Team execution completed");
                    } else {
                        return ToolResult.error(result.toSummary(), "Team execution completed with errors");
                    }
                })
                .onErrorResume(error -> {
                    log.error("Team execution failed", error);
                    return Mono.just(ToolResult.error(
                            "Team execution failed: " + error.getMessage(),
                            "Team execution error"));
                });
    }

    /**
     * 工具参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Params {

        @JsonProperty("tasks")
        @JsonPropertyDescription("要分配给团队的任务列表。每个任务包含描述、优先级和依赖关系")
        private List<TaskParam> tasks;
    }

    /**
     * 单个任务参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskParam {

        @JsonProperty("description")
        @JsonPropertyDescription("任务的详细描述，包含足够的上下文信息")
        private String description;

        @JsonProperty("priority")
        @JsonPropertyDescription("任务优先级，数字越小优先级越高（默认按顺序递增）")
        @Builder.Default
        private int priority = 0;

        @JsonProperty("dependencies")
        @JsonPropertyDescription("依赖的任务 ID 列表（如 ['task-1', 'task-2']），被依赖的任务完成后才能执行此任务")
        private List<String> dependencies;

        @JsonProperty("preferred_teammate")
        @JsonPropertyDescription("优先分配给指定的 Teammate（可选）")
        private String preferredTeammate;
    }
}
