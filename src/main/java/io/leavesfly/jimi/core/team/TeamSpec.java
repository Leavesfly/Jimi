package io.leavesfly.jimi.core.team;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 团队配置规范
 * <p>
 * 对应 agent.yaml 中的 team 配置段，定义团队的组成和调度策略。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamSpec {

    /**
     * 团队名称
     */
    @JsonProperty("name")
    private String name;

    /**
     * 最大并发 Teammate 数
     */
    @JsonProperty("max_concurrency")
    @Builder.Default
    private int maxConcurrency = 3;

    /**
     * 团队执行超时（秒）
     */
    @JsonProperty("timeout_seconds")
    @Builder.Default
    private long timeoutSeconds = 1800;

    /**
     * 调度策略
     */
    @JsonProperty("strategy")
    @Builder.Default
    private TeamStrategy strategy = TeamStrategy.FREE_CLAIM;

    /**
     * Teammate 列表
     */
    @JsonProperty("teammates")
    @Builder.Default
    private List<TeammateSpec> teammates = new ArrayList<>();

    /**
     * 初始任务列表
     */
    @JsonProperty("initial_tasks")
    @Builder.Default
    private List<TeamTaskSpec> initialTasks = new ArrayList<>();
}
