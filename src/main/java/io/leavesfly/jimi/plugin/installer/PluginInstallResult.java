package io.leavesfly.jimi.plugin.installer;

import io.leavesfly.jimi.plugin.spec.PluginSpec;
import lombok.Getter;

import java.nio.file.Path;

/**
 * 插件安装结果
 *
 * <p>记录一次 {@link PluginInstaller} 调用的产物：
 * <ul>
 *   <li>{@link #spec} —— 解析后的插件规范（可用于立即注册到 {@code PluginRegistry}）</li>
 *   <li>{@link #installedDir} —— 插件最终落盘位置（通常是 {@code ~/.jimi/plugins/&lt;name&gt;}）</li>
 *   <li>{@link #source} —— 原始安装来源字符串，便于日志与溯源</li>
 * </ul>
 */
@Getter
public final class PluginInstallResult {

    /** 解析后的插件规范 */
    private final PluginSpec spec;

    /** 安装目标目录 */
    private final Path installedDir;

    /** 原始安装来源（如 {@code owner/repo}、{@code https://...zip}、本地路径） */
    private final String source;

    public PluginInstallResult(PluginSpec spec, Path installedDir, String source) {
        this.spec = spec;
        this.installedDir = installedDir;
        this.source = source;
    }
}
