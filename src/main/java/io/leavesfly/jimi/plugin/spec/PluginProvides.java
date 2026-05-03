package io.leavesfly.jimi.plugin.spec;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件扩展点白名单
 *
 * <p>显式声明插件对外提供的扩展项。即便插件目录下存在更多的文件，
 * 也只有出现在该白名单中的扩展点会被注册到对应 Registry。
 *
 * <p>设计目的：
 * <ol>
 *   <li>安全性：防止插件"夹带私货"，用户可审计</li>
 *   <li>灰度能力：插件作者可分阶段发布，开关独立</li>
 *   <li>可观测：{@code /plugin info} 命令输出此清单便于追溯</li>
 * </ol>
 *
 * <p>对应 {@code plugin.yaml} 片段：
 * <pre>
 * provides:
 *   skills: [my-review]
 *   hooks: [auto-format-java]
 *   commands: [review]
 *   mcp_servers: [filesystem]
 *   agents: [Security-Agent]
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginProvides {

    /**
     * 提供的 Skill 名称列表
     *
     * <p>需与 {@code skills/&lt;name&gt;/SKILL.md} 中的 {@code name} 字段一致。
     */
    @Builder.Default
    private List<String> skills = new ArrayList<>();

    /**
     * 提供的 Hook 名称列表
     *
     * <p>需与 {@code hooks/*.yaml} 中的 {@code name} 字段一致。
     */
    @Builder.Default
    private List<String> hooks = new ArrayList<>();

    /**
     * 提供的自定义命令名称列表
     *
     * <p>需与 {@code commands/*.yaml} 中的 {@code name} 字段一致。
     */
    @Builder.Default
    private List<String> commands = new ArrayList<>();

    /**
     * 提供的 MCP Server 名称列表
     *
     * <p>需与 {@code mcp/servers.json} 中 {@code mcpServers} 的 key 一致。
     */
    @JsonProperty("mcp_servers")
    @Builder.Default
    private List<String> mcpServers = new ArrayList<>();

    /**
     * 提供的 Agent 名称列表
     *
     * <p>需与 {@code agents/&lt;dir&gt;/agent.yaml} 中的 {@code name} 字段一致。
     */
    @Builder.Default
    private List<String> agents = new ArrayList<>();

    /**
     * 判断某个 Skill 名称是否在白名单中。
     * 当 {@link #skills} 为空列表时，视为"未声明白名单"，返回 {@code true}（全部放行）。
     *
     * @param skillName Skill 名称
     * @return 是否允许加载
     */
    public boolean allowsSkill(String skillName) {
        return skills == null || skills.isEmpty() || skills.contains(skillName);
    }

    /**
     * 判断某个 Hook 名称是否在白名单中。
     *
     * @param hookName Hook 名称
     * @return 是否允许加载
     */
    public boolean allowsHook(String hookName) {
        return hooks == null || hooks.isEmpty() || hooks.contains(hookName);
    }

    /**
     * 判断某个命令名称是否在白名单中。
     *
     * @param commandName 命令名称
     * @return 是否允许加载
     */
    public boolean allowsCommand(String commandName) {
        return commands == null || commands.isEmpty() || commands.contains(commandName);
    }

    /**
     * 判断某个 MCP Server 是否在白名单中。
     *
     * @param serverName MCP Server 名称
     * @return 是否允许加载
     */
    public boolean allowsMcpServer(String serverName) {
        return mcpServers == null || mcpServers.isEmpty() || mcpServers.contains(serverName);
    }

    /**
     * 判断某个 Agent 是否在白名单中。
     *
     * @param agentName Agent 名称
     * @return 是否允许加载
     */
    public boolean allowsAgent(String agentName) {
        return agents == null || agents.isEmpty() || agents.contains(agentName);
    }
}
