package io.leavesfly.jimi.plugin.spec;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 插件模块级开关
 *
 * <p>允许用户对单个插件内的不同扩展点模块做细粒度启停。
 * 所有字段默认为 {@code true}，即插件启用时所有模块都加载。
 *
 * <p>对应 {@code plugin.yaml} 片段：
 * <pre>
 * defaults:
 *   modules:
 *     skills: true
 *     hooks: false    # 只禁用 hooks 模块
 *     commands: true
 *     mcp: true
 *     agents: true
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginModuleToggle {

    /** Skills 模块开关 */
    @Builder.Default
    private boolean skills = true;

    /** Hooks 模块开关 */
    @Builder.Default
    private boolean hooks = true;

    /** Commands 模块开关 */
    @Builder.Default
    private boolean commands = true;

    /** MCP 模块开关（P4 实装） */
    @Builder.Default
    private boolean mcp = true;

    /** Agents 模块开关（P4 实装） */
    @Builder.Default
    private boolean agents = true;

    /**
     * 根据模块名查询对应开关状态。
     *
     * @param moduleName 模块名（skills / hooks / commands / mcp / agents）
     * @return 是否启用；未知模块返回 {@code false}
     */
    public boolean isModuleEnabled(String moduleName) {
        if (moduleName == null) {
            return false;
        }
        return switch (moduleName.toLowerCase()) {
            case "skills" -> skills;
            case "hooks" -> hooks;
            case "commands" -> commands;
            case "mcp" -> mcp;
            case "agents" -> agents;
            default -> false;
        };
    }
}
