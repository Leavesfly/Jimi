package io.leavesfly.jimi.plugin.spec;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 插件间依赖声明
 *
 * <p>MVP 阶段仅用于版本范围校验（提示用户依赖的插件版本不符），
 * <b>不做传递加载</b>。未来可在 Phase 2 扩展为自动拉取依赖插件。
 *
 * <p>对应 {@code plugin.yaml} 片段：
 * <pre>
 * dependencies:
 *   - name: git-toolkit
 *     version: "&gt;=0.3.0"
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginDependency {

    /**
     * 依赖的插件名
     */
    private String name;

    /**
     * 版本范围表达式
     *
     * <p>语法参见 {@link PluginCompatibility#getJimiVersion()}。
     */
    private String version;
}
