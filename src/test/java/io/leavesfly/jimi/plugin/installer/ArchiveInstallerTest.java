package io.leavesfly.jimi.plugin.installer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ArchiveInstaller} 单元测试
 *
 * <p>覆盖四个核心能力：
 * <ol>
 *   <li>{@code unzipFile} 正常解压到目标目录</li>
 *   <li>{@code unzipFile} 防御 Zip Slip 攻击（条目路径带 {@code ../} 应抛 IOException）</li>
 *   <li>{@code copyDirectory} 递归复制目录树</li>
 *   <li>{@code deleteDirectory} 递归删除 / 幂等删除</li>
 * </ol>
 *
 * <p>网络下载（{@code downloadFile}）需要真实 HTTP，不在单元测试里覆盖，
 * 由 {@code PluginInstallerTest} 的本地路径/ZIP 安装场景间接验证整条链路。
 */
class ArchiveInstallerTest {

    private ArchiveInstaller archiveInstaller;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        archiveInstaller = new ArchiveInstaller();
    }

    // ==================== unzipFile ====================

    @Test
    @DisplayName("unzipFile: 正常 ZIP 可解压出目录与文件")
    void unzipNormalArchive() throws IOException {
        Path zipFile = tempDir.resolve("normal.zip");
        createZip(zipFile, entry("plugin.yaml", "name: hello"),
                entry("skills/hello/SKILL.md", "# Hello"));

        Path extract = tempDir.resolve("extract");
        archiveInstaller.unzipFile(zipFile, extract);

        Path manifest = extract.resolve("plugin.yaml");
        Path skill = extract.resolve("skills/hello/SKILL.md");
        assertTrue(Files.isRegularFile(manifest));
        assertTrue(Files.isRegularFile(skill));
        assertEquals("name: hello", Files.readString(manifest));
        assertEquals("# Hello", Files.readString(skill));
    }

    @Test
    @DisplayName("unzipFile: Zip Slip（../）必须被阻止")
    void unzipRejectsZipSlip() throws IOException {
        Path zipFile = tempDir.resolve("evil.zip");
        createZip(zipFile, entry("../evil.txt", "pwned"));

        Path extract = tempDir.resolve("extract");

        IOException ex = assertThrows(IOException.class,
                () -> archiveInstaller.unzipFile(zipFile, extract));
        assertTrue(ex.getMessage().contains("Zip Slip"),
                "异常消息应提及 Zip Slip，实际: " + ex.getMessage());

        // 确认受害文件没被写出
        assertFalse(Files.exists(tempDir.resolve("evil.txt")));
    }

    // ==================== copyDirectory ====================

    @Test
    @DisplayName("copyDirectory: 递归复制含嵌套子目录的树")
    void copyDirectoryRecursive() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("a/b"));
        Files.writeString(src.resolve("root.txt"), "root");
        Files.writeString(src.resolve("a/one.txt"), "one");
        Files.writeString(src.resolve("a/b/two.txt"), "two");

        Path dst = tempDir.resolve("dst");
        archiveInstaller.copyDirectory(src, dst);

        assertEquals("root", Files.readString(dst.resolve("root.txt")));
        assertEquals("one", Files.readString(dst.resolve("a/one.txt")));
        assertEquals("two", Files.readString(dst.resolve("a/b/two.txt")));
    }

    @Test
    @DisplayName("copyDirectory: 目标已存在时覆盖")
    void copyDirectoryOverwrites() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("f.txt"), "new");

        Path dst = tempDir.resolve("dst");
        Files.createDirectories(dst);
        Files.writeString(dst.resolve("f.txt"), "old");

        archiveInstaller.copyDirectory(src, dst);

        assertEquals("new", Files.readString(dst.resolve("f.txt")));
    }

    // ==================== deleteDirectory ====================

    @Test
    @DisplayName("deleteDirectory: 递归删除目录及其所有内容")
    void deleteDirectoryRecursive() throws IOException {
        Path dir = tempDir.resolve("doomed");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("sub/b.txt"), "b");

        archiveInstaller.deleteDirectory(dir);

        assertFalse(Files.exists(dir));
    }

    @Test
    @DisplayName("deleteDirectory: 目录不存在时幂等无异常")
    void deleteNonExistentIsIdempotent() {
        Path ghost = tempDir.resolve("does-not-exist");
        assertFalse(Files.exists(ghost));
        // 不应抛异常
        assertDoesNotThrowIOException(() -> archiveInstaller.deleteDirectory(ghost));
        assertDoesNotThrowIOException(() -> archiveInstaller.deleteDirectory(null));
    }

    @Test
    @DisplayName("unzip + copy: 组合验证字节层面一致")
    void unzipThenCopyPreservesBytes() throws IOException {
        byte[] payload = "二进制内容 binary".getBytes(StandardCharsets.UTF_8);
        Path zipFile = tempDir.resolve("bytes.zip");
        createZipBinary(zipFile, "data.bin", payload);

        Path extract = tempDir.resolve("extract");
        archiveInstaller.unzipFile(zipFile, extract);

        Path dst = tempDir.resolve("dst");
        archiveInstaller.copyDirectory(extract, dst);

        assertArrayEquals(payload, Files.readAllBytes(dst.resolve("data.bin")));
    }

    // ==================== 辅助方法 ====================

    /** 单个 ZIP 条目（name + UTF-8 文本内容） */
    private static ZipItem entry(String name, String content) {
        return new ZipItem(name, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 用多条 UTF-8 文本条目组装 ZIP 文件 */
    private static void createZip(Path zipFile, ZipItem... items) throws IOException {
        Files.createDirectories(zipFile.getParent());
        try (OutputStream fos = Files.newOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (ZipItem item : items) {
                zos.putNextEntry(new ZipEntry(item.name));
                zos.write(item.bytes);
                zos.closeEntry();
            }
        }
    }

    /** 写单个二进制 ZIP 条目 */
    private static void createZipBinary(Path zipFile, String name, byte[] payload) throws IOException {
        Files.createDirectories(zipFile.getParent());
        try (OutputStream fos = Files.newOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(payload);
            zos.closeEntry();
        }
    }

    private static void assertDoesNotThrowIOException(IOAction action) {
        try {
            action.run();
        } catch (Exception e) {
            throw new AssertionError("Expected no exception but got: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface IOAction {
        void run() throws Exception;
    }

    private record ZipItem(String name, byte[] bytes) {
    }
}
