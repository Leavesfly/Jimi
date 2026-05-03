package io.leavesfly.jimi.plugin.installer;

import io.leavesfly.jimi.plugin.PluginLoader;
import io.leavesfly.jimi.plugin.spec.PluginScope;
import io.leavesfly.jimi.plugin.spec.PluginSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 插件安装器
 *
 * <p>支持三种安装来源：
 * <ol>
 *   <li><b>GitHub 仓库</b>：{@code owner/repo} 或 {@code owner/repo/subpath}
 *       → 下载 {@code https://github.com/<owner>/<repo>/archive/refs/heads/main.zip}</li>
 *   <li><b>HTTP(S) URL</b>：以 {@code http://} 或 {@code https://} 开头、以 {@code .zip} 结尾</li>
 *   <li><b>本地路径</b>：指向含 {@code plugin.yaml} 的本地目录或 {@code .zip} 文件</li>
 * </ol>
 *
 * <p>统一流程：
 * <ol>
 *   <li>解析来源 → 下载/定位 ZIP / 直接使用目录</li>
 *   <li>解压到临时目录</li>
 *   <li>递归定位含 {@code plugin.yaml} 的根目录</li>
 *   <li>复用 {@link PluginLoader#parsePluginManifest} 解析清单（含 validate）</li>
 *   <li>将目录复制到 {@code ~/.jimi/plugins/<name>/}</li>
 *   <li>清理临时文件</li>
 * </ol>
 *
 * <p>不直接把插件注册到 {@code PluginRegistry}——由调用方在安装后显式调用
 * {@code PluginRegistry.reload()} 或重启，以保持职责单一。
 */
@Slf4j
@Service
public class PluginInstaller {

    /** GitHub 归档下载地址模板：{@code owner / repo / branch} */
    private static final String GITHUB_ARCHIVE_TEMPLATE =
            "https://github.com/%s/%s/archive/refs/heads/%s.zip";

    /**
     * {@code owner/repo} 语法未显式指定分支时，按顺序尝试的 fallback 分支。
     *
     * <p>先试 {@code main}（GitHub 2020 年后新仓库的默认值），
     * 失败后再试 {@code master}（老仓库的默认值）。
     */
    private static final String[] DEFAULT_GITHUB_BRANCHES = {"main", "master"};

    /** 插件清单文件名 */
    private static final String PLUGIN_YAML = "plugin.yaml";

    /** 临时目录前缀 */
    private static final String TEMP_DIR_PREFIX = "jimi-plugin-install-";

    @Autowired
    private ArchiveInstaller archiveInstaller;

    @Autowired
    private PluginLoader pluginLoader;

    /**
     * 统一入口：根据来源字符串自动分派到对应的安装方法。
     *
     * <p>分派规则：
     * <ul>
     *   <li>包含 {@code ://} → {@link #installFromUrl}</li>
     *   <li>本地文件或目录存在 → {@link #installFromLocal}</li>
     *   <li>形如 {@code owner/repo} → {@link #installFromGitHub}</li>
     * </ul>
     *
     * @param source 安装来源
     * @return 安装结果
     */
    public PluginInstallResult install(String source) {
        Objects.requireNonNull(source, "source must not be null");
        String trimmed = source.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("source must not be blank");
        }

        if (trimmed.contains("://")) {
            return installFromUrl(trimmed);
        }

        Path localPath = Paths.get(trimmed);
        if (Files.exists(localPath)) {
            return installFromLocal(localPath);
        }

        return installFromGitHub(trimmed);
    }

    /**
     * 从 GitHub 仓库安装。
     *
     * <p>支持的语法（按优先级）：
     * <ul>
     *   <li>{@code owner/repo#branch}            —— 显式指定分支</li>
     *   <li>{@code owner/repo/subpath#branch}    —— 显式指定分支 + 子路径</li>
     *   <li>{@code owner/repo}                    —— 按 {@link #DEFAULT_GITHUB_BRANCHES} 顺序尝试</li>
     *   <li>{@code owner/repo/subpath}            —— 同上</li>
     * </ul>
     *
     * <p>未显式指定分支时，先尝试 {@code main}，遇到 404 再 fallback 到 {@code master}；
     * 任何非 404 的失败都直接抛出，不做 fallback（避免把网络问题误判为分支问题）。
     *
     * @param repoSpec 形如 {@code owner/repo[/subpath][#branch]}
     * @return 安装结果
     */
    public PluginInstallResult installFromGitHub(String repoSpec) {
        // 1. 先把 #branch 后缀剥离出来
        String explicitBranch = null;
        String pathPart = repoSpec;
        int hashIdx = repoSpec.indexOf('#');
        if (hashIdx >= 0) {
            explicitBranch = repoSpec.substring(hashIdx + 1).trim();
            pathPart = repoSpec.substring(0, hashIdx);
            if (explicitBranch.isEmpty()) {
                throw new IllegalArgumentException(
                        "Invalid GitHub spec: " + repoSpec + " (branch after '#' is empty)");
            }
        }

        // 2. 解析 owner / repo / subpath
        String[] parts = pathPart.split("/");
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid GitHub spec: " + repoSpec + " (expected owner/repo[/subpath][#branch])");
        }
        String owner = parts[0];
        String repo = parts[1];
        String subPath = parts.length > 2
                ? String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length))
                : null;

        // 3. 构造候选分支列表
        String[] candidateBranches = explicitBranch != null
                ? new String[]{explicitBranch}
                : DEFAULT_GITHUB_BRANCHES;

        log.info("Installing plugin from GitHub: owner={}, repo={}, subPath={}, branches={}",
                owner, repo, subPath, java.util.Arrays.toString(candidateBranches));

        // 4. 逐个分支尝试：只有 HTTP 404 才 fallback，其他 HTTP 错误直接抛（避免把网络问题误判为分支问题）
        ArchiveInstaller.HttpStatusException lastBranch404 = null;
        for (String branch : candidateBranches) {
            String archiveUrl = String.format(GITHUB_ARCHIVE_TEMPLATE, owner, repo, branch);
            try {
                return doInstallFromZipUrl(archiveUrl, repoSpec, subPath);
            } catch (ArchiveInstaller.HttpStatusException e) {
                if (e.getStatusCode() == 404) {
                    log.info("Branch '{}' not found (HTTP 404) for {}/{}; will try next candidate",
                            branch, owner, repo);
                    lastBranch404 = e;
                } else {
                    // 非 404（如 401/403/500/…）直接抛出，不 fallback
                    throw new RuntimeException(
                            "Failed to download GitHub archive for " + repoSpec
                                    + " branch=" + branch + " (HTTP " + e.getStatusCode() + ")", e);
                }
            }
        }

        // 5. 所有候选都 404
        throw new RuntimeException(
                "Failed to download GitHub archive for " + repoSpec
                        + " (all candidate branches returned HTTP 404: "
                        + java.util.Arrays.toString(candidateBranches) + ")",
                lastBranch404);
    }

    /**
     * 从 HTTP(S) URL 安装（期望是 ZIP 归档）。
     *
     * <p>与 {@link #installFromGitHub} 不同，这里没有"候选分支"概念，
     * 下载 404 也直接包装为 {@link RuntimeException} 抛出，不做 fallback。
     *
     * @param url ZIP 归档 URL
     * @return 安装结果
     */
    public PluginInstallResult installFromUrl(String url) {
        log.info("Installing plugin from URL: {}", url);
        try {
            return doInstallFromZipUrl(url, url, null);
        } catch (ArchiveInstaller.HttpStatusException e) {
            // URL 安装无 fallback 语义，统一当作失败抛出
            throw new RuntimeException("Failed to download archive: " + url
                    + " (HTTP " + e.getStatusCode() + ")", e);
        }
    }

    /**
     * 从本地路径安装（目录或 ZIP 文件）。
     *
     * @param localPath 本地目录或 {@code .zip} 文件
     * @return 安装结果
     */
    public PluginInstallResult installFromLocal(Path localPath) {
        log.info("Installing plugin from local path: {}", localPath);
        if (!Files.exists(localPath)) {
            throw new IllegalArgumentException("Local path does not exist: " + localPath);
        }

        Path tempDir = createTempDir();
        try {
            Path pluginRoot;
            if (Files.isDirectory(localPath)) {
                pluginRoot = findPluginRoot(localPath);
            } else if (localPath.toString().toLowerCase().endsWith(".zip")) {
                Path extractDir = tempDir.resolve("extracted");
                archiveInstaller.unzipFile(localPath, extractDir);
                pluginRoot = findPluginRoot(extractDir);
            } else {
                throw new IllegalArgumentException(
                        "Local path must be a directory or .zip file: " + localPath);
            }

            if (pluginRoot == null) {
                throw new IllegalStateException("plugin.yaml not found under: " + localPath);
            }
            return copyToUserPlugins(pluginRoot, localPath.toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to install from local path: " + localPath, e);
        } finally {
            safeDelete(tempDir);
        }
    }

    /**
     * 卸载已安装的插件（移除 {@code ~/.jimi/plugins/&lt;name&gt;}）。
     *
     * <p>仅删除 USER 层插件目录，不触碰 CLASSPATH 与 PROJECT 层。
     * 卸载后调用方需要自行调用 {@code PluginRegistry.reload()} 刷新状态。
     *
     * @param pluginName 插件名
     * @return 是否实际删除了目录
     */
    public boolean uninstall(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            throw new IllegalArgumentException("pluginName must not be blank");
        }
        Path target = pluginLoader.getUserPluginsDirectory().resolve(pluginName);
        if (!Files.isDirectory(target)) {
            log.warn("Plugin '{}' not found at user layer: {}", pluginName, target);
            return false;
        }
        try {
            archiveInstaller.deleteDirectory(target);
            log.info("Plugin '{}' uninstalled from {}", pluginName, target);
            return true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to uninstall plugin: " + pluginName, e);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 从 ZIP URL 安装的统一流程。
     *
     * <p>下载阶段的 HTTP 错误会以 {@link ArchiveInstaller.HttpStatusException} 原样抛出，
     * 供上层（如 {@link #installFromGitHub}）基于状态码做分支 fallback；其他 IO 错误仍
     * 包装为 {@link RuntimeException}。
     *
     * @param archiveUrl   ZIP 下载地址
     * @param sourceLabel  记录到结果中的来源标签
     * @param subPath      可选的 ZIP 内部子路径（GitHub 仓库内指定子目录时用）
     * @return 安装结果
     * @throws ArchiveInstaller.HttpStatusException 下载阶段返回非 2xx 响应时（原样透出）
     */
    private PluginInstallResult doInstallFromZipUrl(String archiveUrl, String sourceLabel, String subPath)
            throws ArchiveInstaller.HttpStatusException {
        Path tempDir = createTempDir();
        try {
            Path zipFile = tempDir.resolve("archive.zip");
            // HttpStatusException 直接透出给上层做精细化处理；其他 IOException 统一兜底
            archiveInstaller.downloadFile(archiveUrl, zipFile);

            Path extractDir = tempDir.resolve("extracted");
            archiveInstaller.unzipFile(zipFile, extractDir);

            Path searchRoot = extractDir;
            if (subPath != null && !subPath.isEmpty()) {
                // GitHub 归档解压后为 <repo>-<branch>/... 结构，需先进入唯一顶层目录
                Path topLevel = firstDirectChild(extractDir);
                if (topLevel != null) {
                    Path candidate = topLevel.resolve(subPath);
                    if (Files.isDirectory(candidate)) {
                        searchRoot = candidate;
                    }
                }
            }

            Path pluginRoot = findPluginRoot(searchRoot);
            if (pluginRoot == null) {
                throw new IllegalStateException(
                        "plugin.yaml not found in archive: " + archiveUrl);
            }
            return copyToUserPlugins(pluginRoot, sourceLabel);
        } catch (ArchiveInstaller.HttpStatusException e) {
            // 透传给调用方（installFromGitHub）做分支 fallback
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("Failed to install from " + archiveUrl, e);
        } finally {
            safeDelete(tempDir);
        }
    }

    /**
     * 把插件目录复制到 {@code ~/.jimi/plugins/&lt;name&gt;/} 并解析清单。
     *
     * <p><b>原子切换策略</b>（保证"升级中途失败不损坏旧版"）：
     * <ol>
     *   <li>先把新内容完整复制到 {@code target.new}（暂存目录）</li>
     *   <li>若旧目录 {@code target} 存在，把它 rename 到 {@code target.bak}</li>
     *   <li>把 {@code target.new} rename 到 {@code target}</li>
     *   <li>全部成功后删除 {@code target.bak}</li>
     * </ol>
     *
     * <p>任一步失败都会触发回滚：
     * <ul>
     *   <li>步骤 1 失败 → 删掉半成品 {@code target.new}，旧版未动，抛异常</li>
     *   <li>步骤 2/3 失败 → 把 {@code target.bak} 改回 {@code target}，同时清掉 {@code target.new}</li>
     * </ul>
     */
    private PluginInstallResult copyToUserPlugins(Path pluginRoot, String sourceLabel) throws IOException {
        // 先解析一次以拿到插件名（也顺带做了 validate）
        PluginSpec parsed = pluginLoader.parsePluginManifest(pluginRoot, PluginScope.USER);

        Path userPluginsDir = pluginLoader.getUserPluginsDirectory();
        Files.createDirectories(userPluginsDir);
        Path target = userPluginsDir.resolve(parsed.getName());
        Path stagingDir = userPluginsDir.resolve(parsed.getName() + ".new");
        Path backupDir = userPluginsDir.resolve(parsed.getName() + ".bak");

        // 清掉任何历史残留（上次失败留下的 .new / .bak）
        if (Files.exists(stagingDir)) {
            archiveInstaller.deleteDirectory(stagingDir);
        }
        if (Files.exists(backupDir)) {
            archiveInstaller.deleteDirectory(backupDir);
        }

        // 步骤 1：复制到暂存目录
        try {
            Files.createDirectories(stagingDir);
            archiveInstaller.copyDirectory(pluginRoot, stagingDir);
        } catch (IOException | RuntimeException e) {
            // 复制失败：清掉半成品，旧版完整保留
            safeDeleteQuietly(stagingDir);
            throw new IOException(
                    "Failed to stage plugin '" + parsed.getName() + "' to " + stagingDir, e);
        }

        // 步骤 2：把旧目录重命名为 .bak（存在时才做）
        boolean hadOldVersion = Files.exists(target);
        if (hadOldVersion) {
            try {
                Files.move(target, backupDir, StandardCopyOption.REPLACE_EXISTING);
                log.info("Existing plugin at {} backed up to {}", target, backupDir);
            } catch (IOException e) {
                // 备份失败：清掉暂存，旧版仍在原位
                safeDeleteQuietly(stagingDir);
                throw new IOException(
                        "Failed to back up existing plugin '" + parsed.getName() + "'", e);
            }
        }

        // 步骤 3：把暂存目录切换为正式目录
        try {
            Files.move(stagingDir, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 切换失败：回滚——把备份恢复回去
            if (hadOldVersion) {
                try {
                    Files.move(backupDir, target, StandardCopyOption.REPLACE_EXISTING);
                    log.warn("Rolled back plugin '{}' from {} after switch failure",
                            parsed.getName(), backupDir);
                } catch (IOException rollbackFail) {
                    log.error("CRITICAL: failed to rollback plugin '{}'. backup={}, target={}",
                            parsed.getName(), backupDir, target, rollbackFail);
                }
            }
            safeDeleteQuietly(stagingDir);
            throw new IOException(
                    "Failed to promote staged plugin '" + parsed.getName() + "' to " + target, e);
        }

        // 步骤 4：最终清理备份（失败不影响可用性，降级到 warn）
        if (hadOldVersion) {
            safeDeleteQuietly(backupDir);
        }

        // 复制完后再次解析（指向最终目录），避免 pluginDir 指向临时路径
        PluginSpec finalSpec = pluginLoader.parsePluginManifest(target, PluginScope.USER);

        log.info("Plugin '{}' installed to {} (atomic-switch)", finalSpec.getName(), target);
        return new PluginInstallResult(finalSpec, target, sourceLabel);
    }

    /** 删除目录，失败仅记 warn，不抛出——用于清理步骤。 */
    private void safeDeleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            archiveInstaller.deleteDirectory(dir);
        } catch (Exception e) {
            log.warn("Failed to clean up {}: {}", dir, e.getMessage());
        }
    }

    /**
     * {@link #findPluginRoot(Path)} 的最大向下搜索深度。
     *
     * <p>覆盖常见的归档结构：
     * <ul>
     *   <li>深度 0：{@code root/plugin.yaml}（本地目录直接就是插件根）</li>
     *   <li>深度 1：{@code root/<pkg>/plugin.yaml}（GitHub zip 顶层包装一层 {@code repo-branch/}）</li>
     *   <li>深度 2-3：{@code root/<pkg>/<subpath>/plugin.yaml}（单仓库放多个插件或子目录形式）</li>
     * </ul>
     * 超过 3 层认为不是合法插件归档，避免误匹配深埋的同名文件。
     */
    private static final int MAX_PLUGIN_ROOT_DEPTH = 3;

    /**
     * 递归查找含 {@code plugin.yaml} 的目录（最多向下搜索 {@value #MAX_PLUGIN_ROOT_DEPTH} 层）。
     *
     * @param root 搜索根
     * @return 首个匹配目录；未找到返回 {@code null}
     */
    private Path findPluginRoot(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }
        // 优先：root 自身
        if (Files.isRegularFile(root.resolve(PLUGIN_YAML))) {
            return root;
        }
        // 其次：在不超过 MAX_PLUGIN_ROOT_DEPTH 层深度范围内查找
        try (Stream<Path> walk = Files.walk(root, MAX_PLUGIN_ROOT_DEPTH)) {
            return walk.filter(p -> PLUGIN_YAML.equals(p.getFileName().toString()))
                    .filter(Files::isRegularFile)
                    .map(Path::getParent)
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * 获取某目录下唯一的直接子目录（用于剥离 GitHub 归档的顶层包装）。
     */
    private Path firstDirectChild(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory).findFirst().orElse(null);
        }
    }

    /** 创建临时目录（失败时抛 RuntimeException，避免 checked 泄漏） */
    private Path createTempDir() {
        try {
            return Files.createTempDirectory(TEMP_DIR_PREFIX);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp dir", e);
        }
    }

    /** 安全删除目录：任何异常仅记日志，不抛出 */
    private void safeDelete(Path dir) {
        try {
            archiveInstaller.deleteDirectory(dir);
        } catch (Exception e) {
            log.warn("Failed to clean up temp dir {}: {}", dir, e.getMessage());
        }
    }
}
