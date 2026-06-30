package io.leavesfly.jimi.core.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

 import io.leavesfly.jimi.team.TeamSpec;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent规范配置
 * 对应agent.yaml中的配置结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSpec {

    /**
     * Agent名称
     */
    @JsonProperty("name")
    private String name;

    /**
     * Agent 描述（Claude Code 标准必需字段）。
     * <p>用于 SubAgentTool 工具描述、/agents 命令展示以及自动委派决策。
     * <p>Claude Code 的 .md 格式中对应 frontmatter 的 {@code description} 字段。
     */
    @JsonProperty("description")
    private String description;
    
    /**
     * 系统提示词文件路径（相对于agent.yaml的路径）
     */
    @JsonProperty("system_prompt_path")
    private Path systemPromptPath;
    
    /**
     * 系统提示词参数（用于模板替换）
     */
    @JsonProperty("system_prompt_args")
    @Builder.Default
    private Map<String, String> systemPromptArgs = new HashMap<>();
    
    /**
     * 工具列表（格式：module:ClassName）
     */
    @JsonProperty("tools")
    private List<String> tools;
    
    /**
     * 排除的工具列表（Jimi 原生命名）
     */
    @JsonProperty("exclude_tools")
    private List<String> excludeTools;

    /**
     * 禁用的工具列表（Claude Code 标准字段，与 {@link #excludeTools} 等价的别名）。
     * <p>当两个字段同时存在时，合并取并集。
     */
    @JsonProperty("disallowedTools")
    private List<String> disallowedTools;

    /**
     * 最大轮次（Claude Code 标准可选字段）。
     * <p>限制 Agent 的最大交互轮次，未设置时使用全局默认值。
     */
    @JsonProperty("maxTurns")
    private Integer maxTurns;

    /**
     * 内联系统提示词（来自 .md 文件的 body）。
     * <p>非空时优先于 {@link #systemPromptPath} 使用，对应 Claude Code .md 格式中
     * frontmatter 之后的 Markdown body。
     */
    @JsonProperty("inlineSystemPrompt")
    private String inlineSystemPrompt;
    
    /**
     * 指定的模型名称（可选）
     * 如果设置，则优先使用此模型，否则使用全局配置的默认模型
     */
    @JsonProperty("model")
    private String model;
    
    /**
     * 子Agent配置
     */
    @JsonProperty("subagents")
    @Builder.Default
    private Map<String, SubagentSpec> subagents = new HashMap<>();

    /**
     * Agent Teams 配置（可选）
     * 定义团队的组成、调度策略和初始任务
     */
    @JsonProperty("team")
    private TeamSpec team;
}
