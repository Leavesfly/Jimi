package io.leavesfly.jimi.plugin.spec;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件兼容性约束
 *
 * <p>声明插件对 Jimi 版本、Java 版本和操作系统的要求，
 * 在加载阶段由 {@code PluginLoader} 进行校验，不满足的插件会被拒绝加载。
 *
 * <p>对应 {@code plugin.yaml} 片段：
 * <pre>
 * compatibility:
 *   jimi_version: "&gt;=1.0.0,&lt;2.0.0"
 *   java_version: "&gt;=17"
 *   os: [linux, mac]
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginCompatibility {

    /**
     * Jimi 版本范围表达式（语义化版本）
     *
     * <p>支持的语法：
     * <ul>
     *   <li>{@code ">=1.0.0"}：大于等于</li>
     *   <li>{@code "<2.0.0"}：小于</li>
     *   <li>{@code ">=1.0.0,<2.0.0"}：逗号分隔的多个约束（AND 语义）</li>
     * </ul>
     *
     * <p>为空表示不做版本限制。
     */
    @JsonProperty("jimi_version")
    private String jimiVersion;

    /**
     * Java 版本要求
     *
     * <p>示例：{@code ">=17"}。为空表示不做限制。
     */
    @JsonProperty("java_version")
    private String javaVersion;

    /**
     * 支持的操作系统列表
     *
     * <p>合法值：{@code linux} / {@code mac} / {@code windows}。
     * 为空表示全平台支持。
     */
    @Builder.Default
    private List<String> os = new ArrayList<>();
}
