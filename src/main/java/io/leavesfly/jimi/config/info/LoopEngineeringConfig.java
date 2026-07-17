package io.leavesfly.jimi.config.info;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Loop Engineering 配置
 * <p>
 * 包含 /loop 和 /goal 命令、Worktree 隔离、状态持久化的所有可调参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoopEngineeringConfig {

    /**
     * 是否启用 Loop Engineering 功能
     */
    @JsonProperty("enabled")
    @Builder.Default
    private boolean enabled = true;

    // ==================== /loop 调度配置 ====================

    /**
     * 最大并发循环数
     */
    @JsonProperty("max_concurrent_loops")
    @Builder.Default
    private int maxConcurrentLoops = 3;

    /**
     * 调度线程池大小
     */
    @JsonProperty("schedule_thread_pool_size")
    @Builder.Default
    private int scheduleThreadPoolSize = 2;

    /**
     * /loop 最大迭代次数（0 表示不限制）
     * 防止无人值守时无限循环烧 token
     */
    @JsonProperty("loop_max_iterations")
    @Builder.Default
    private int loopMaxIterations = 100;

    /**
     * /loop 超时时间（分钟，0 表示不限制）
     */
    @JsonProperty("loop_timeout_minutes")
    @Builder.Default
    private int loopTimeoutMinutes = 240;

    /**
     * /loop 连续失败熔断次数（达到后自动停止循环）
     */
    @JsonProperty("loop_max_consecutive_failures")
    @Builder.Default
    private int loopMaxConsecutiveFailures = 3;

    // ==================== /goal 控制配置 ====================

    /**
     * /goal 最大迭代次数（防止无限循环）
     */
    @JsonProperty("goal_max_iterations")
    @Builder.Default
    private int goalMaxIterations = 50;

    /**
     * 单次 goal 的 token 预算
     */
    @JsonProperty("goal_max_tokens")
    @Builder.Default
    private long goalMaxTokens = 500000;

    /**
     * 每 N 步验证一次目标是否满足
     */
    @JsonProperty("goal_verify_interval")
    @Builder.Default
    private int goalVerifyInterval = 3;

    /**
     * goal 超时时间（分钟）
     */
    @JsonProperty("goal_timeout_minutes")
    @Builder.Default
    private int goalTimeoutMinutes = 60;

    /**
     * 验证者使用的模型（空则使用默认模型）
     * 建议使用轻量快速模型做验证，与执行者模型分离
     */
    @JsonProperty("goal_verifier_model")
    @Builder.Default
    private String goalVerifierModel = "";

    /**
     * 确定性验证命令的超时时间（秒）
     * 用于 /goal --verify "cmd" 的命令退出码验证通道
     */
    @JsonProperty("goal_verify_command_timeout_seconds")
    @Builder.Default
    private int goalVerifyCommandTimeoutSeconds = 300;

    // ==================== Worktree 配置 ====================

    /**
     * 是否启用 Git Worktree 隔离
     */
    @JsonProperty("worktree_enabled")
    @Builder.Default
    private boolean worktreeEnabled = true;

    /**
     * Worktree 存放目录（相对于项目根目录）
     */
    @JsonProperty("worktree_base_dir")
    @Builder.Default
    private String worktreeBaseDir = ".jimi/worktrees";

    /**
     * 任务完成后自动清理 worktree
     */
    @JsonProperty("worktree_auto_cleanup")
    @Builder.Default
    private boolean worktreeAutoCleanup = true;

    /**
     * Worktree 分支前缀
     */
    @JsonProperty("worktree_branch_prefix")
    @Builder.Default
    private String worktreeBranchPrefix = "jimi/";

    /**
     * /goal 达成并验证通过后，是否自动将 worktree 分支合并回目标分支
     * false 时保留 worktree 与分支，等待人工审查合并
     */
    @JsonProperty("worktree_auto_merge")
    @Builder.Default
    private boolean worktreeAutoMerge = true;

    // ==================== State 状态持久化配置 ====================

    /**
     * 默认状态文件路径（相对于项目根目录）
     */
    @JsonProperty("default_state_file")
    @Builder.Default
    private String defaultStateFile = ".jimi/progress.md";

    /**
     * 是否自动更新状态文件
     */
    @JsonProperty("state_auto_update")
    @Builder.Default
    private boolean stateAutoUpdate = true;
}
