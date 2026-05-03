package io.leavesfly.jimi.plugin.spec;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件规范（对应 {@code plugin.yaml} 的根对象）
 *
 * <p>封装一个 Jimi 插件的完整元信息、兼容性约束、扩展点白名单、
 * 默认状态与生命周期脚本。加载链路：
 * <pre>
 * plugin.yaml（文件）
 *   └── PluginLoader.parsePluginManifest
 *         └── PluginSpec（本对象）
 *               └── PluginDispatcher.dispatch
 *                     └── 分发给各 {@code PluginModuleAdapter}
 * </pre>
 *
 * <p>最小清单示例：
 * <pre>
 * name: java-dev-kit
 * version: 1.2.0
 * description: Java 开发者全套扩展
 * </pre>
 *
 * @see PluginScope 作用域枚举
 * @see PluginCompatibility 兼容性约束
 * @see PluginProvides 扩展点白名单
 * @see PluginDefaults 默认启用状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginSpec {

    /**
     * 插件唯一标识（必需）
     *
     * <p>建议使用 kebab-case，全局唯一。同名插件后加载的覆盖先加载的。
     */
    private String name;

    /**
     * 插件语义化版本号（必需）
     *
     * <p>例如 {@code 1.2.0} 或 {@code 0.1.0-SNAPSHOT}。
     */
    private String version;

    /**
     * 插件简短描述（必需）
     */
    private String description;

    /**
     * 作者信息（可选）
     */
    private String author;

    /**
     * 主页 URL（可选）
     */
    private String homepage;

    /**
     * 代码仓库 URL（可选）
     */
    private String repository;

    /**
     * 许可证标识（可选），如 {@code Apache-2.0} / {@code MIT}
     */
    private String license;

    /**
     * 关键词列表，用于插件市场检索（可选）
     */
    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    /**
     * 兼容性约束（可选但强烈推荐）
     *
     * <p>声明对 Jimi 版本、Java 版本、操作系统的要求，
     * {@code PluginLoader} 在加载阶段进行校验。
     */
    private PluginCompatibility compatibility;

    /**
     * 扩展点白名单（可选）
     *
     * <p>显式声明插件对外提供的 Skills / Hooks / Commands / MCP / Agents。
     * 未显式列出的扩展项不会被注册。
     */
    @Builder.Default
    private PluginProvides provides = PluginProvides.builder().build();

    /**
     * 默认启用状态与模块开关（可选）
     */
    @Builder.Default
    private PluginDefaults defaults = PluginDefaults.builder().build();

    /**
     * 运行环境依赖（可选）
     */
    private PluginRequirements requirements;

    /**
     * 插件间依赖（可选，MVP 阶段仅作版本校验）
     */
    @Builder.Default
    private List<PluginDependency> dependencies = new ArrayList<>();

    /**
     * 生命周期脚本（可选，脚本执行器在后续阶段实装）
     */
    private PluginLifecycle lifecycle;

    // ==================== 运行时字段（不从 YAML 反序列化） ====================

    /**
     * 插件根目录的绝对路径（运行时由 Loader 填充）
     */
    private transient Path pluginDir;

    /**
     * 插件来源作用域（运行时由 Loader 填充）
     */
    private transient PluginScope scope;

    // ==================== 便捷访问方法 ====================

    /**
     * 判断插件是否默认启用。
     *
     * @return 是否启用
     */
    public boolean isEnabled() {
        return defaults == null || defaults.isEnabled();
    }

    /**
     * 判断指定模块是否启用（已考虑插件级开关与模块级开关的 AND 关系）。
     *
     * @param moduleName 模块名（skills / hooks / commands / mcp / agents）
     * @return 启用状态
     */
    public boolean isModuleEnabled(String moduleName) {
        if (!isEnabled()) {
            return false;
        }
        PluginModuleToggle toggle = defaults != null ? defaults.getModules() : null;
        if (toggle == null) {
            return true;
        }
        return toggle.isModuleEnabled(moduleName);
    }

    /**
     * 校验插件规范的必需字段，失败时抛出 {@link IllegalArgumentException}。
     *
     * <p>由 {@code PluginLoader} 在解析完成后调用。
     *
     * @throws IllegalArgumentException 若必需字段缺失或非法
     */
    public void validate() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Plugin name is required");
        }
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalArgumentException("Plugin version is required for: " + name);
        }
        if (description == null || description.trim().isEmpty()) {
            throw new IllegalArgumentException("Plugin description is required for: " + name);
        }
    }
}
