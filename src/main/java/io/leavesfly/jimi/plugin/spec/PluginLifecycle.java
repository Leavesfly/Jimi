package io.leavesfly.jimi.plugin.spec;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 插件生命周期脚本配置
 *
 * <p>声明插件在不同生命周期阶段触发的脚本路径（相对插件根目录）。
 * 所有脚本均为可选配置，未配置的阶段不执行任何动作。
 *
 * <p>对应 {@code plugin.yaml} 片段：
 * <pre>
 * lifecycle:
 *   on_install:   "scripts/post-install.sh"
 *   on_enable:    "scripts/on-enable.sh"
 *   on_disable:   "scripts/on-disable.sh"
 *   on_uninstall: "scripts/pre-uninstall.sh"
 * </pre>
 *
 * <p>注意：MVP（P0~P1）阶段仅定义字段，脚本执行器在后续阶段实装。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginLifecycle {

    /**
     * 安装完成后执行（文件落盘后、注册前）
     */
    @JsonProperty("on_install")
    private String onInstall;

    /**
     * 启用时执行（注册到各 Registry 后）
     */
    @JsonProperty("on_enable")
    private String onEnable;

    /**
     * 禁用时执行（从各 Registry 移除后）
     */
    @JsonProperty("on_disable")
    private String onDisable;

    /**
     * 卸载前执行（文件删除前）
     */
    @JsonProperty("on_uninstall")
    private String onUninstall;
}
