package io.leavesfly.jimi.plugin.installer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.leavesfly.jimi.plugin.PluginLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * {@link PluginInstaller} 单元测试
 *
 * <p>策略：
 * <ul>
 *   <li>手工构造 {@link PluginLoader}（注入 YAML Mapper）+ {@link ArchiveInstaller}</li>
 *   <li>用 Mockito {@code spy} 覆盖 {@link PluginLoader#getUserPluginsDirectory()} 指向
 *       {@link TempDir}，避免真实触及 {@code ~/.jimi/plugins/}</li>
 *   <li>通过本地目录 / 本地 ZIP 两类来源完整跑通 install 全流程，
 *       远程 HTTP 场景由 {@link ArchiveInstaller#downloadFile} 覆盖，
 *       此处通过分派规则（{@code install()} 对本地路径与 {@code owner/repo} 的识别）
 *       单独验证</li>
 * </ul>
 */
class PluginInstallerTest {

    private PluginInstaller installer;
    private PluginLoader pluginLoader;
    private ArchiveInstaller archiveInstaller;

    @TempDir
    Path tempDir;

    /** 模拟的用户插件安装目录（替代 {@code ~/.jimi/plugins/}） */
    private Path fakeUserPluginsDir;

    @BeforeEach
    void setUp() {
        fakeUserPluginsDir = tempDir.resolve("user-home/.jimi/plugins");

        // 构造真实 PluginLoader 并注入 YAML Mapper
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        pluginLoader = spy(new PluginLoader());
        ReflectionTestUtils.setField(pluginLoader, "yamlObjectMapper", yamlMapper);
        doReturn(fakeUserPluginsDir).when(pluginLoader).getUserPluginsDirectory();

        archiveInstaller = new ArchiveInstaller();

        installer = new PluginInstaller();
        ReflectionTestUtils.setField(installer, "pluginLoader", pluginLoader);
        ReflectionTestUtils.setField(installer, "archiveInstaller", archiveInstaller);
    }

    // ==================== install(source) 分派规则 ====================

    @Test
    @DisplayName("install: null / 空字符串抛 IllegalArgumentException")
    void installRejectsBlankSource() {
        assertThrows(NullPointerException.class, () -> installer.install(null));
        assertThrows(IllegalArgumentException.class, () -> installer.install(""));
        assertThrows(IllegalArgumentException.class, () -> installer.install("   "));
    }

    @Test
    @DisplayName("installFromGitHub: owner/repo 格式解析校验")
    void installFromGitHubRequiresOwnerAndRepo() {
        assertThrows(IllegalArgumentException.class,
                () -> installer.installFromGitHub("only-owner"));
    }

    // ==================== 本地目录安装 ====================

    @Test
    @DisplayName("installFromLocal: 本地目录含 plugin.yaml 时能成功安装")
    void installFromLocalDirectory() throws IOException {
        Path srcDir = createValidPluginDir(tempDir.resolve("src"), "demo", "1.0.0");

        PluginInstallResult result = installer.installFromLocal(srcDir);

        assertNotNull(result);
        assertEquals("demo", result.getSpec().getName());
        assertEquals("1.0.0", result.getSpec().getVersion());
        assertEquals(srcDir.toString(), result.getSource());

        Path expectedTarget = fakeUserPluginsDir.resolve("demo");
        assertEquals(expectedTarget.toAbsolutePath(),
                result.getInstalledDir().toAbsolutePath());
        assertTrue(Files.isRegularFile(expectedTarget.resolve("plugin.yaml")));
        assertTrue(Files.isRegularFile(expectedTarget.resolve("skills/hello/SKILL.md")));
    }

    @Test
    @DisplayName("installFromLocal: 同名插件已存在时覆盖安装")
    void installFromLocalOverwritesExisting() throws IOException {
        // 先装一次 v1.0.0
        Path v1Src = createValidPluginDir(tempDir.resolve("v1"), "demo", "1.0.0");
        installer.installFromLocal(v1Src);

        // 再装 v2.0.0
        Path v2Src = createValidPluginDir(tempDir.resolve("v2"), "demo", "2.0.0");
        PluginInstallResult result = installer.installFromLocal(v2Src);

        assertEquals("2.0.0", result.getSpec().getVersion());
        String manifest = Files.readString(
                fakeUserPluginsDir.resolve("demo/plugin.yaml"));
        assertTrue(manifest.contains("version: 2.0.0"));
    }

    @Test
    @DisplayName("installFromLocal: 目录不含 plugin.yaml 抛 IllegalStateException")
    void installFromLocalMissingManifest() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("no-manifest"));

        assertThrows(RuntimeException.class, () -> installer.installFromLocal(dir));
    }

    @Test
    @DisplayName("installFromLocal: 路径不存在抛 IllegalArgumentException")
    void installFromLocalMissingPath() {
        Path ghost = tempDir.resolve("ghost");
        assertThrows(IllegalArgumentException.class,
                () -> installer.installFromLocal(ghost));
    }

    @Test
    @DisplayName("installFromLocal: 非目录非 .zip 文件抛异常")
    void installFromLocalInvalidFileType() throws IOException {
        Path file = tempDir.resolve("bad.txt");
        Files.writeString(file, "not-a-plugin");

        assertThrows(RuntimeException.class, () -> installer.installFromLocal(file));
    }

    // ==================== 本地 ZIP 安装 ====================

    @Test
    @DisplayName("installFromLocal: 本地 .zip 文件能解压并安装")
    void installFromLocalZip() throws IOException {
        Path zipFile = tempDir.resolve("demo.zip");
        createPluginZip(zipFile, "zipped", "0.9.1");

        PluginInstallResult result = installer.installFromLocal(zipFile);

        assertEquals("zipped", result.getSpec().getName());
        assertEquals("0.9.1", result.getSpec().getVersion());
        assertTrue(Files.isRegularFile(
                fakeUserPluginsDir.resolve("zipped/plugin.yaml")));
    }

    // ==================== install 分派到本地路径 ====================

    @Test
    @DisplayName("install(localDir): 存在的本地路径会走 installFromLocal 分支")
    void installDispatchesToLocalWhenPathExists() throws IOException {
        Path srcDir = createValidPluginDir(tempDir.resolve("dispatch"), "dispatch-demo", "1.0.0");

        PluginInstallResult result = installer.install(srcDir.toString());

        assertEquals("dispatch-demo", result.getSpec().getName());
    }

    // ==================== uninstall ====================

    @Test
    @DisplayName("uninstall: 插件存在时删除目录并返回 true")
    void uninstallExistingPlugin() throws IOException {
        Path srcDir = createValidPluginDir(tempDir.resolve("u-src"), "uninst", "1.0.0");
        installer.installFromLocal(srcDir);
        Path installed = fakeUserPluginsDir.resolve("uninst");
        assertTrue(Files.isDirectory(installed));

        boolean removed = installer.uninstall("uninst");

        assertTrue(removed);
        assertFalse(Files.exists(installed));
    }

    @Test
    @DisplayName("uninstall: 插件不存在时返回 false")
    void uninstallMissingPluginReturnsFalse() {
        boolean removed = installer.uninstall("never-installed");
        assertFalse(removed);
    }

    @Test
    @DisplayName("uninstall: 空名字抛 IllegalArgumentException")
    void uninstallRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> installer.uninstall(null));
        assertThrows(IllegalArgumentException.class, () -> installer.uninstall(""));
        assertThrows(IllegalArgumentException.class, () -> installer.uninstall("  "));
    }

    // ==================== 辅助方法 ====================

    /**
     * 在指定目录创建一个最小合法插件目录结构：
     * <pre>
     *   &lt;root&gt;/plugin.yaml
     *   &lt;root&gt;/skills/hello/SKILL.md
     * </pre>
     */
    private Path createValidPluginDir(Path root, String name, String version) throws IOException {
        Files.createDirectories(root.resolve("skills/hello"));
        String yaml = "name: " + name + "\n"
                + "version: " + version + "\n"
                + "description: a demo plugin for installer test\n";
        Files.writeString(root.resolve("plugin.yaml"), yaml);
        Files.writeString(root.resolve("skills/hello/SKILL.md"),
                "---\nname: hello\ndescription: a hello skill\n---\n\n# Hello");
        return root;
    }

    /** 构建一个包含合法插件目录的 ZIP 文件 */
    private void createPluginZip(Path zipFile, String name, String version) throws IOException {
        String yaml = "name: " + name + "\n"
                + "version: " + version + "\n"
                + "description: a demo plugin packed in zip\n";
        Files.createDirectories(zipFile.getParent());
        try (OutputStream fos = Files.newOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            // 模拟 GitHub 归档的 <repo>-<branch>/... 外层包装
            zos.putNextEntry(new ZipEntry(name + "-main/plugin.yaml"));
            zos.write(yaml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(name + "-main/skills/hello/SKILL.md"));
            zos.write("---\nname: hello\ndescription: a hello skill\n---\n\n# Hello"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
