package io.leavesfly.jimi.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jimi.plugin.spec.PluginCompatibility;
import io.leavesfly.jimi.plugin.spec.PluginRequirements;
import io.leavesfly.jimi.plugin.spec.PluginScope;
import io.leavesfly.jimi.plugin.spec.PluginSpec;
import io.leavesfly.jimi.plugin.util.VersionRange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * 插件加载器
 *
 * <p>负责：
 * <ol>
 *   <li>扫描三层插件目录（classpath / 用户级 / 项目级）</li>
 *   <li>解析每个插件根目录下的 {@code plugin.yaml} 清单文件为 {@link PluginSpec}</li>
 *   <li>执行 {@link PluginCompatibility} 约束检查与 {@link PluginRequirements} 环境检查</li>
 * </ol>
 *
 * <p><b>加载策略</b>：
 * <ul>
 *   <li>{@link #loadAllFromClasspath} / {@link #loadAllFromUserHome} / {@link #loadAllFromProject}：
 *       返回 {@link LoadedPlugin}，<b>包含</b>兼容性/环境校验不通过的插件及其拒绝原因，
 *       由调用方（通常是 {@code PluginRegistry}）决定如何展示 rejected 状态。</li>
 *   <li>{@link #loadFromClasspath} / {@link #loadFromUserHome} / {@link #loadFromProject}：
 *       向后兼容的旧接口，<b>仅</b>返回通过校验的 {@link PluginSpec}；解析失败的条目依旧被丢弃。</li>
 * </ul>
 *
 * <p>不负责扩展点的实际加载和注册——这些工作由 {@code PluginDispatcher} 委托给各
 * {@code PluginModuleAdapter} 完成。
 *
 * <p>目录约定：
 * <pre>
 * classpath:/plugins/&lt;plugin-name&gt;/plugin.yaml
 * ~/.jimi/plugins/&lt;plugin-name&gt;/plugin.yaml
 * &lt;project&gt;/.jimi/plugins/&lt;plugin-name&gt;/plugin.yaml
 * </pre>
 *
 * <p>JAR 包内的 classpath 扫描无法动态遍历子目录，故采用"硬编码白名单 + 按名读取"的
 * 策略（与 {@code SkillLoader.loadSkillsFromClasspath} 一致）。新增官方内置插件时需同时
 * 更新 {@link #BUILTIN_CLASSPATH_PLUGINS} 常量。
 */
@Slf4j
@Service
public class PluginLoader {

    /** 插件清单文件名 */
    public static final String MANIFEST_FILENAME = "plugin.yaml";

    /** 插件子目录在 classpath / ~/.jimi / &lt;project&gt;/.jimi 下的统一命名 */
    public static final String PLUGINS_DIR_NAME = "plugins";

    /**
     * JAR 模式下 classpath 内置插件的名称白名单
     *
     * <p>受限于 Java 的 classpath 遍历机制，JAR 模式不能像文件系统那样枚举子目录，
     * 必须按已知名称显式查找。新增内置插件时在此添加目录名，
     * 对应 {@code src/main/resources/plugins/&lt;name&gt;/plugin.yaml}。
     *
     * <p>当前没有官方内置插件，保持空数组；文件系统模式（开发环境）下不受此限制——
     * 但仍会按 {@link #TEST_FIXTURE_PLUGIN_NAMES} 规则过滤掉专用测试夹具。
     */
    public static final String[] BUILTIN_CLASSPATH_PLUGINS = new String[]{};

    /**
     * 文件系统模式（开发环境 / IDE Run）下需要从 classpath 过滤掉的"纯测试夹具"名。
     *
     * <p>背景：{@code src/test/resources/plugins/&lt;name&gt;/} 的资源在 {@code mvn test}
     * 或 IDE 直接 Run 应用时会被合并进运行时 classpath，{@code loadAllFromClasspath}
     * 扫描目录时会误把它们当成内置插件加载，污染 Skill/Hook/Command 等 Registry。
     *
     * <p>此处硬编码一份夹具名黑名单，仅在 CLASSPATH 扫描阶段生效，不影响 USER/PROJECT 层
     * （用户若真的在 {@code ~/.jimi/plugins/} 下放这个名字的插件，走 USER 路径不受限）。
     */
    private static final java.util.Set<String> TEST_FIXTURE_PLUGIN_NAMES = java.util.Set.of(
            "hello-world"
    );

    /** 从 MANIFEST 读取不到版本号时的默认值（与 {@code WelcomeRenderer#getVersionInfo} 对齐） */
    private static final String DEFAULT_JIMI_VERSION = "0.1.0";

    /** classpath 命令执行的超时时间（秒），用于 {@code requirements.binaries.check} */
    private static final long REQUIREMENT_CHECK_TIMEOUT_SECONDS = 10L;

    @Autowired
    @Qualifier("yamlObjectMapper")
    private ObjectMapper yamlObjectMapper;

    /**
     * 从 classpath 加载 {@code plugins/} 目录下的所有插件（<b>仅</b>通过校验的）。
     *
     * <p>向后兼容的旧接口。需要感知 rejected 状态的调用方应使用 {@link #loadAllFromClasspath}。
     *
     * @return 解析并校验通过的插件列表
     */
    public List<PluginSpec> loadFromClasspath() {
        return filterAccepted(loadAllFromClasspath());
    }

    /**
     * 从 classpath 加载 {@code plugins/} 目录下的所有插件（<b>含</b> rejected）。
     *
     * <p>文件系统模式（开发环境、{@code target/classes} 结构）下遍历 {@code plugins/} 子目录；
     * JAR 模式下按 {@link #BUILTIN_CLASSPATH_PLUGINS} 白名单逐一读取。
     *
     * <p>返回列表中包含所有解析成功的插件，无论其兼容性/环境校验是否通过。
     * 对于校验失败的插件，{@link LoadedPlugin#getRejectReason()} 会记录原因。
     *
     * @return 加载结果列表（含 rejected）
     */
    public List<LoadedPlugin> loadAllFromClasspath() {
        try {
            URL resource = getClass().getClassLoader().getResource(PLUGINS_DIR_NAME);
            if (resource == null) {
                log.debug("No classpath plugins/ directory found");
                return loadBuiltinPluginsFromJar();
            }
            if ("jar".equals(resource.getProtocol())) {
                return loadBuiltinPluginsFromJar();
            }
            Path dir = Paths.get(resource.toURI());
            List<LoadedPlugin> scanned = loadAllFromDirectory(dir, PluginScope.CLASSPATH);
            return filterOutTestFixtures(scanned);
        } catch (Exception e) {
            log.warn("Failed to load plugins from classpath: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从 CLASSPATH 扫描结果中剔除 {@link #TEST_FIXTURE_PLUGIN_NAMES} 中的专用夹具，
     * 保证 IDE 直接 Run 应用（此时 test-classes 会被挂到运行时 classpath）时不会
     * 意外加载测试夹具插件。
     *
     * <p>被剔除的条目会以 INFO 级别日志留痕，便于定位"为什么 fixture 没生效"。
     *
     * @param scanned {@link #loadAllFromDirectory} 返回的原始扫描结果
     * @return 剔除夹具后的插件列表
     */
    private List<LoadedPlugin> filterOutTestFixtures(List<LoadedPlugin> scanned) {
        if (scanned.isEmpty()) {
            return scanned;
        }
        List<LoadedPlugin> kept = new ArrayList<>(scanned.size());
        for (LoadedPlugin lp : scanned) {
            String name = lp.getSpec().getName();
            if (TEST_FIXTURE_PLUGIN_NAMES.contains(name)) {
                log.info("Skipping test fixture '{}' from classpath scan (see PluginLoader.TEST_FIXTURE_PLUGIN_NAMES)",
                        name);
                continue;
            }
            kept.add(lp);
        }
        return kept;
    }

    /**
     * JAR 模式下按白名单读取内置插件。
     *
     * <p>返回值<b>包含</b>校验失败的插件条目及其拒绝原因。
     */
    private List<LoadedPlugin> loadBuiltinPluginsFromJar() {
        if (BUILTIN_CLASSPATH_PLUGINS.length == 0) {
            log.debug("No builtin classpath plugins declared");
            return Collections.emptyList();
        }

        List<LoadedPlugin> result = new ArrayList<>();
        for (String pluginName : BUILTIN_CLASSPATH_PLUGINS) {
            String resourcePath = PLUGINS_DIR_NAME + "/" + pluginName + "/" + MANIFEST_FILENAME;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    log.warn("Declared builtin plugin '{}' not found at classpath:{}",
                            pluginName, resourcePath);
                    continue;
                }
                PluginSpec spec = yamlObjectMapper.readValue(in, PluginSpec.class);
                spec.setPluginDir(Paths.get("classpath:" + PLUGINS_DIR_NAME + "/" + pluginName));
                spec.setScope(PluginScope.CLASSPATH);
                spec.validate();

                result.add(evaluateChecks(spec));
            } catch (Exception e) {
                log.error("Failed to load builtin plugin '{}': {}", pluginName, e.getMessage());
            }
        }
        log.info("Loaded {} builtin plugin(s) from JAR classpath (incl. rejected)", result.size());
        return result;
    }

    /**
     * 加载用户级插件（{@code ~/.jimi/plugins/}），<b>仅</b>通过校验的。
     *
     * @return 解析并校验通过的插件列表
     */
    public List<PluginSpec> loadFromUserHome() {
        return filterAccepted(loadAllFromUserHome());
    }

    /**
     * 加载用户级插件（{@code ~/.jimi/plugins/}），<b>含</b> rejected。
     *
     * @return 加载结果列表
     */
    public List<LoadedPlugin> loadAllFromUserHome() {
        Path dir = getUserPluginsDirectory();
        return loadAllFromDirectory(dir, PluginScope.USER);
    }

    /**
     * 加载项目级插件（{@code &lt;project&gt;/.jimi/plugins/}），<b>仅</b>通过校验的。
     *
     * @param projectDir 项目根目录，{@code null} 时返回空列表
     * @return 解析并校验通过的插件列表
     */
    public List<PluginSpec> loadFromProject(Path projectDir) {
        return filterAccepted(loadAllFromProject(projectDir));
    }

    /**
     * 加载项目级插件（{@code &lt;project&gt;/.jimi/plugins/}），<b>含</b> rejected。
     *
     * @param projectDir 项目根目录，{@code null} 时返回空列表
     * @return 加载结果列表
     */
    public List<LoadedPlugin> loadAllFromProject(Path projectDir) {
        if (projectDir == null) {
            return Collections.emptyList();
        }
        Path dir = getProjectPluginsDirectory(projectDir);
        return loadAllFromDirectory(dir, PluginScope.PROJECT);
    }

    /**
     * 扫描指定目录下所有一级子目录，将每个含 {@code plugin.yaml} 的子目录解析为
     * {@link PluginSpec}，<b>仅</b>返回通过校验的插件（向后兼容旧接口）。
     *
     * @param pluginsRoot 插件根目录（{@code plugins/}）
     * @param scope       作用域（用于填充 {@link PluginSpec#getScope()}）
     * @return 解析并校验通过的插件列表；目录不存在或全部被过滤时返回空列表
     */
    public List<PluginSpec> loadFromDirectory(Path pluginsRoot, PluginScope scope) {
        return filterAccepted(loadAllFromDirectory(pluginsRoot, scope));
    }

    /**
     * 扫描指定目录下所有一级子目录，将每个含 {@code plugin.yaml} 的子目录解析为
     * {@link PluginSpec}，并<b>记录</b>其兼容性与环境依赖校验结果（不过滤）。
     *
     * <p>解析失败（YAML 语法错、必需字段缺失）的插件仍然会被跳过，因为无法生成
     * 可展示的 {@link PluginSpec}。只有"解析成功但校验失败"的条目会作为
     * {@link LoadedPlugin#isRejected()} == true 返回。
     *
     * @param pluginsRoot 插件根目录（{@code plugins/}）
     * @param scope       作用域（用于填充 {@link PluginSpec#getScope()}）
     * @return 加载结果列表；目录不存在时返回空列表
     */
    public List<LoadedPlugin> loadAllFromDirectory(Path pluginsRoot, PluginScope scope) {
        if (pluginsRoot == null || !Files.exists(pluginsRoot) || !Files.isDirectory(pluginsRoot)) {
            log.debug("Plugins directory not found or not a directory: {}", pluginsRoot);
            return Collections.emptyList();
        }

        List<LoadedPlugin> result = new ArrayList<>();
        try (Stream<Path> children = Files.list(pluginsRoot)) {
            children.filter(Files::isDirectory).forEach(pluginDir -> {
                Path manifest = pluginDir.resolve(MANIFEST_FILENAME);
                if (!Files.exists(manifest)) {
                    log.debug("Skip directory without plugin.yaml: {}", pluginDir);
                    return;
                }
                try {
                    PluginSpec spec = parsePluginManifest(pluginDir, scope);
                    result.add(evaluateChecks(spec));
                } catch (Exception e) {
                    log.error("Failed to load plugin at {}: {}", pluginDir, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.error("Failed to list plugins directory: {}", pluginsRoot, e);
        }

        long acceptedCount = result.stream().filter(LoadedPlugin::isAccepted).count();
        log.info("Loaded {} plugin(s) from {} (scope={}, accepted={}, rejected={})",
                result.size(), pluginsRoot, scope, acceptedCount, result.size() - acceptedCount);
        return result;
    }

    /**
     * 从 {@link LoadedPlugin} 列表中过滤出通过校验的 {@link PluginSpec}，
     * 供向后兼容的旧接口使用。
     */
    private List<PluginSpec> filterAccepted(List<LoadedPlugin> loaded) {
        List<PluginSpec> accepted = new ArrayList<>(loaded.size());
        for (LoadedPlugin lp : loaded) {
            if (lp.isAccepted()) {
                accepted.add(lp.getSpec());
            }
        }
        return accepted;
    }

    /**
     * 解析单个插件目录的 {@code plugin.yaml} 清单。
     *
     * <p>仅负责"解析 + 必需字段校验"，不做兼容性与环境依赖检查
     * （这两步由 {@link #evaluateChecks(PluginSpec)} 统一执行）。
     *
     * @param pluginDir 插件根目录（包含 {@code plugin.yaml}）
     * @param scope     作用域
     * @return 解析并通过必需字段校验的 {@link PluginSpec}
     * @throws IOException              若清单文件读取失败
     * @throws IllegalArgumentException 若清单字段非法
     */
    public PluginSpec parsePluginManifest(Path pluginDir, PluginScope scope) throws IOException {
        Path manifest = pluginDir.resolve(MANIFEST_FILENAME);
        PluginSpec spec = yamlObjectMapper.readValue(manifest.toFile(), PluginSpec.class);
        spec.setPluginDir(pluginDir.toAbsolutePath());
        spec.setScope(scope);
        spec.validate();
        return spec;
    }

    /**
     * 对插件依次执行兼容性校验和环境依赖校验，把结果打包为 {@link LoadedPlugin}。
     *
     * <p>不抛异常、不丢弃插件——拒绝原因会写入 {@link LoadedPlugin#getRejectReason()}，
     * 由上游（通常是 {@code PluginRegistry}）决定如何展示。
     *
     * @param spec 插件规范
     * @return 校验结果打包后的 {@link LoadedPlugin}
     */
    private LoadedPlugin evaluateChecks(PluginSpec spec) {
        CheckResult compatResult = checkCompatibility(spec, getCurrentJimiVersion());
        if (!compatResult.passed()) {
            log.warn("Plugin '{}' rejected by compatibility check: {}",
                    spec.getName(), compatResult.reason());
            return LoadedPlugin.rejected(spec, compatResult.reason());
        }

        CheckResult reqResult = checkRequirements(spec);
        if (!reqResult.passed()) {
            log.warn("Plugin '{}' rejected by requirements check: {}",
                    spec.getName(), reqResult.reason());
            return LoadedPlugin.rejected(spec, reqResult.reason());
        }
        return LoadedPlugin.accepted(spec);
    }

    /**
     * 校验插件的兼容性约束（Jimi 版本、Java 版本、操作系统）。
     *
     * @param spec        插件规范
     * @param jimiVersion 当前运行的 Jimi 版本
     * @return 校验结果对象，包含是否通过与失败原因
     */
    public CheckResult checkCompatibility(PluginSpec spec, String jimiVersion) {
        PluginCompatibility c = spec.getCompatibility();
        if (c == null) {
            return CheckResult.ok();
        }

        if (c.getJimiVersion() != null && !c.getJimiVersion().isBlank()
                && !VersionRange.matches(c.getJimiVersion(), jimiVersion)) {
            return CheckResult.fail(String.format(
                    "requires Jimi %s, but running %s", c.getJimiVersion(), jimiVersion));
        }

        if (c.getJavaVersion() != null && !c.getJavaVersion().isBlank()) {
            String currentJava = System.getProperty("java.specification.version");
            if (!VersionRange.matches(c.getJavaVersion(), currentJava)) {
                return CheckResult.fail(String.format(
                        "requires Java %s, but running %s", c.getJavaVersion(), currentJava));
            }
        }

        if (c.getOs() != null && !c.getOs().isEmpty()) {
            String currentOs = detectOsName();
            boolean match = c.getOs().stream()
                    .map(String::toLowerCase)
                    .anyMatch(currentOs::equalsIgnoreCase);
            if (!match) {
                return CheckResult.fail(String.format(
                        "requires OS %s, but running %s", c.getOs(), currentOs));
            }
        }

        return CheckResult.ok();
    }

    /**
     * 校验插件的环境依赖（可执行命令、环境变量、必需文件）。
     *
     * <p>检查规则：
     * <ul>
     *   <li><b>binaries</b>：若声明 {@code check} 命令，执行并以返回码 0 视为通过；
     *       否则按 {@code which <name>} 判断可执行文件在 {@code PATH} 中是否可用</li>
     *   <li><b>env_vars</b>：环境变量必须存在且非空</li>
     *   <li><b>files</b>：绝对路径按绝对路径查找；相对路径优先相对插件目录查找，
     *       插件目录下找不到时回退到进程工作目录</li>
     * </ul>
     *
     * @param spec 插件规范
     * @return 校验结果；任一项失败即返回 fail
     */
    public CheckResult checkRequirements(PluginSpec spec) {
        PluginRequirements req = spec.getRequirements();
        if (req == null) {
            return CheckResult.ok();
        }

        if (req.getBinaries() != null) {
            for (PluginRequirements.BinaryRequirement bin : req.getBinaries()) {
                CheckResult r = checkBinary(bin);
                if (!r.passed()) {
                    return r;
                }
            }
        }

        if (req.getEnvVars() != null) {
            for (String var : req.getEnvVars()) {
                if (var == null || var.isBlank()) {
                    continue;
                }
                String value = System.getenv(var);
                if (value == null || value.isEmpty()) {
                    return CheckResult.fail("required env var not set: " + var);
                }
            }
        }

        if (req.getFiles() != null) {
            for (String f : req.getFiles()) {
                if (f == null || f.isBlank()) {
                    continue;
                }
                if (!resolveRequiredFile(f, spec.getPluginDir())) {
                    return CheckResult.fail("required file not found: " + f);
                }
            }
        }

        return CheckResult.ok();
    }

    /**
     * 校验单个可执行命令依赖。
     *
     * <p><b>跨平台策略</b>：根据 {@link #detectOsName()} 选择合适的 shell 执行 check 命令：
     * <ul>
     *   <li>{@code windows} → {@code cmd.exe /c <check>}；默认探测为 {@code where <name> >nul 2>&1}</li>
     *   <li>其他（{@code linux} / {@code mac} / POSIX 兼容）→ {@code /bin/sh -c <check>}；
     *       默认探测为 {@code command -v <name> >/dev/null 2>&1}</li>
     * </ul>
     *
     * <p>退出码 {@code 0} 视为通过；超时、非 0、启动失败均视为不通过，
     * 原因会写入 {@link CheckResult#reason()}。
     */
    private CheckResult checkBinary(PluginRequirements.BinaryRequirement bin) {
        if (bin == null || bin.getName() == null || bin.getName().isBlank()) {
            return CheckResult.ok();
        }

        boolean isWindows = "windows".equals(detectOsName());
        String checkCmd = (bin.getCheck() != null && !bin.getCheck().isBlank())
                ? bin.getCheck()
                : defaultBinaryProbe(bin.getName(), isWindows);

        ProcessBuilder pb = isWindows
                ? new ProcessBuilder("cmd.exe", "/c", checkCmd).redirectErrorStream(true)
                : new ProcessBuilder("/bin/sh", "-c", checkCmd).redirectErrorStream(true);

        try {
            Process p = pb.start();
            boolean finished = p.waitFor(REQUIREMENT_CHECK_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return CheckResult.fail(buildBinaryFailureMessage(bin, "check command timed out"));
            }
            if (p.exitValue() != 0) {
                return CheckResult.fail(buildBinaryFailureMessage(bin,
                        "check command exited with code " + p.exitValue()));
            }
            return CheckResult.ok();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return CheckResult.fail(buildBinaryFailureMessage(bin,
                    "check command failed: " + e.getMessage()));
        }
    }

    /**
     * 生成默认的可执行命令探测脚本。
     *
     * <p>按 OS 选择不同策略：
     * <ul>
     *   <li>Windows：{@code where <name> >nul 2>&1}，依赖系统 PATH 查找</li>
     *   <li>POSIX：{@code command -v '<name>' >/dev/null 2>&1}，兼容 zsh/bash</li>
     * </ul>
     *
     * @param name      可执行命令名
     * @param isWindows 是否 Windows 平台
     * @return 可直接交给对应 shell 执行的探测脚本
     */
    private String defaultBinaryProbe(String name, boolean isWindows) {
        if (isWindows) {
            // Windows cmd.exe 不支持单引号转义，依赖 where 的白名单式参数解析；
            // 为阻断命令注入，剔除 & | < > ^ " 等元字符（合法的可执行名不会包含这些）
            String safeName = name.replaceAll("[&|<>^\"\\s]", "");
            return "where " + safeName + " >nul 2>&1";
        }
        String safeName = name.replace("'", "'\\''");
        return "command -v '" + safeName + "' >/dev/null 2>&1";
    }

    private String buildBinaryFailureMessage(PluginRequirements.BinaryRequirement bin, String detail) {
        StringBuilder sb = new StringBuilder("binary '").append(bin.getName())
                .append("' unavailable (").append(detail).append(")");
        if (bin.getInstallHint() != null && !bin.getInstallHint().isBlank()) {
            sb.append("; install hint: ").append(bin.getInstallHint());
        }
        return sb.toString();
    }

    /**
     * 解析 {@code requirements.files} 中的路径：
     * 绝对路径直接判断存在性；相对路径先相对插件目录，再回退到进程工作目录。
     */
    private boolean resolveRequiredFile(String fileRef, Path pluginDir) {
        Path p = Paths.get(fileRef);
        if (p.isAbsolute()) {
            return Files.exists(p);
        }
        if (pluginDir != null) {
            Path underPlugin = pluginDir.resolve(fileRef);
            if (Files.exists(underPlugin)) {
                return true;
            }
        }
        Path underCwd = Paths.get(System.getProperty("user.dir")).resolve(fileRef);
        return Files.exists(underCwd);
    }

    /**
     * 获取当前运行的 Jimi 版本号。
     *
     * <p>优先从本类所在 JAR 的 MANIFEST.MF 读取 {@code Implementation-Version}，
     * 读取失败时回退到 {@link #DEFAULT_JIMI_VERSION}。与 {@code WelcomeRenderer#getVersionInfo}
     * 的策略保持一致。
     *
     * @return 版本号字符串（如 {@code "0.1.0"}）
     */
    public String getCurrentJimiVersion() {
        Package pkg = PluginLoader.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        if (version == null || version.isBlank()) {
            return DEFAULT_JIMI_VERSION;
        }
        return version;
    }

    /**
     * 获取用户级插件目录（{@code ~/.jimi/plugins/}）。
     *
     * @return 目录路径
     */
    public Path getUserPluginsDirectory() {
        return Paths.get(System.getProperty("user.home"), ".jimi", PLUGINS_DIR_NAME);
    }

    /**
     * 获取项目级插件目录（{@code &lt;project&gt;/.jimi/plugins/}）。
     *
     * @param projectDir 项目根目录
     * @return 目录路径
     */
    public Path getProjectPluginsDirectory(Path projectDir) {
        return projectDir.resolve(".jimi").resolve(PLUGINS_DIR_NAME);
    }

    /**
     * 获取内置插件白名单（测试与调试用）。
     *
     * @return 不可修改的名称列表
     */
    public List<String> getBuiltinClasspathPlugins() {
        return Collections.unmodifiableList(Arrays.asList(BUILTIN_CLASSPATH_PLUGINS));
    }

    /**
     * 检测当前操作系统短名（{@code linux} / {@code mac} / {@code windows}）。
     *
     * @return 小写的 OS 名
     */
    private String detectOsName() {
        String raw = System.getProperty("os.name", "").toLowerCase();
        if (raw.contains("mac") || raw.contains("darwin")) {
            return "mac";
        }
        if (raw.contains("win")) {
            return "windows";
        }
        if (raw.contains("nux") || raw.contains("nix") || raw.contains("aix")) {
            return "linux";
        }
        return raw;
    }

    /**
     * 校验结果。
     *
     * @param passed 是否通过
     * @param reason 失败原因（通过时为 {@code null}）
     */
    public record CheckResult(boolean passed, String reason) {

        /**
         * 创建"通过"结果。
         *
         * @return 通过的结果
         */
        public static CheckResult ok() {
            return new CheckResult(true, null);
        }

        /**
         * 创建"失败"结果。
         *
         * @param reason 失败原因（人类可读）
         * @return 失败的结果
         */
        public static CheckResult fail(String reason) {
            return new CheckResult(false, reason);
        }
    }

    /**
     * 单个插件的加载结果。
     *
     * <p>承载"解析成功的 {@link PluginSpec}" + "可选的拒绝原因"。
     * 由 {@link #loadAllFromDirectory} / {@link #loadAllFromUserHome} /
     * {@link #loadAllFromProject} / {@link #loadAllFromClasspath} 返回，
     * 让调用方（通常是 {@code PluginRegistry}）根据 {@link #isRejected()} /
     * {@link #isAccepted()} 决定注册还是以 rejected 状态展示。
     *
     * @param spec         插件规范（必非 {@code null}，且已经通过必需字段校验）
     * @param rejectReason 拒绝原因；{@code null} 表示通过所有校验
     */
    public record LoadedPlugin(PluginSpec spec, String rejectReason) {

        /**
         * 创建"通过校验"的加载结果。
         *
         * @param spec 插件规范
         * @return 加载结果
         */
        public static LoadedPlugin accepted(PluginSpec spec) {
            return new LoadedPlugin(spec, null);
        }

        /**
         * 创建"被拒绝"的加载结果。
         *
         * @param spec   插件规范
         * @param reason 拒绝原因
         * @return 加载结果
         */
        public static LoadedPlugin rejected(PluginSpec spec, String reason) {
            return new LoadedPlugin(spec, reason);
        }

        /** @return 是否通过了兼容性与环境依赖校验 */
        public boolean isAccepted() {
            return rejectReason == null;
        }

        /** @return 是否被拒绝（任一校验失败） */
        public boolean isRejected() {
            return rejectReason != null;
        }

        /** 兼容 Lombok 风格的 getter */
        public PluginSpec getSpec() {
            return spec;
        }

        /** 兼容 Lombok 风格的 getter */
        public String getRejectReason() {
            return rejectReason;
        }
    }
}
