package io.leavesfly.jimi.plugin.spec;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件运行环境依赖声明
 *
 * <p>用于安装时的前置检查，缺失依赖时插件不会被启用，
 * 避免运行期才爆出 {@code command not found} 类错误。
 *
 * <p>对应 {@code plugin.yaml} 片段：
 * <pre>
 * requirements:
 *   binaries:
 *     - name: google-java-format
 *       check: "google-java-format --version"
 *       install_hint: "brew install google-java-format"
 *   env_vars:
 *     - JAVA_HOME
 *   files:
 *     - pom.xml
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginRequirements {

    /**
     * 必需的可执行命令列表
     */
    @Builder.Default
    private List<BinaryRequirement> binaries = new ArrayList<>();

    /**
     * 必需的环境变量名列表
     */
    @Builder.Default
    private List<String> envVars = new ArrayList<>();

    /**
     * 必需存在的文件（相对项目根目录或绝对路径）
     */
    @Builder.Default
    private List<String> files = new ArrayList<>();

    /**
     * 单个可执行命令的依赖声明。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BinaryRequirement {

        /**
         * 命令名（将通过 {@code which <name>} 检查存在性）
         */
        private String name;

        /**
         * 可选的自定义检查命令（优先级高于 {@link #name}）
         *
         * <p>例如 {@code "google-java-format --version"}，
         * 执行后返回码 {@code 0} 视为满足。
         */
        private String check;

        /**
         * 安装提示（给用户的人类可读文案）
         *
         * <p>例如 {@code "brew install google-java-format"}。
         */
        private String installHint;
    }
}
