package io.leavesfly.jimi.plugin.spec;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 插件默认启用配置
 *
 * <p>描述插件在首次安装或加载时的默认状态，用户可通过
 * {@code /plugin enable/disable} 命令或 {@code ~/.jimi/plugins-config.yaml}
 * 全局配置覆盖。
 *
 * <p>对应 {@code plugin.yaml} 片段：
 * <pre>
 * defaults:
 *   enabled: true
 *   modules:
 *     skills: true
 *     hooks: true
 *     ...
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginDefaults {

    /**
     * 插件是否默认启用
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * 各扩展点模块的默认开关
     */
    @Builder.Default
    private PluginModuleToggle modules = PluginModuleToggle.builder().build();
}
