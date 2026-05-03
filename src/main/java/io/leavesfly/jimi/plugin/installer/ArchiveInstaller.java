package io.leavesfly.jimi.plugin.installer;

import io.leavesfly.jimi.common.HttpClientConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 通用归档（ZIP）安装工具
 *
 * <p>从 {@code SkillsInstaller} 抽取出的通用能力，聚焦三件事：
 * <ol>
 *   <li>{@link #downloadFile(String, Path)} —— HTTP 下载（带重定向处理）</li>
 *   <li>{@link #unzipFile(Path, Path)}       —— ZIP 解压（带 Zip Slip 防护）</li>
 *   <li>{@link #copyDirectory(Path, Path)} / {@link #deleteDirectory(Path)} —— 本地目录操作</li>
 * </ol>
 *
 * <p>本类不感知 Skill / Plugin 等业务概念，仅作为 {@code PluginInstaller} 与
 * {@code SkillsInstaller} 的共同底座。后续若有其他"可安装物"亦可复用。
 */
@Slf4j
@Service
public class ArchiveInstaller {

    /** HTTP 客户端标识 */
    private static final String USER_AGENT = "Jimi-ArchiveInstaller/1.0";

    /** 最大重定向层级，避免无限循环 */
    private static final int MAX_REDIRECTS = 5;

    /**
     * 下载远程文件到本地。
     *
     * <p>自动处理 301/302/303 重定向（最多 {@value #MAX_REDIRECTS} 层）。
     * 非 200 状态码会抛异常。
     *
     * @param urlString  源 URL
     * @param targetPath 目标文件路径
     * @throws IOException 网络或磁盘 IO 错误
     */
    public void downloadFile(String urlString, Path targetPath) throws IOException {
        downloadFileInternal(urlString, targetPath, 0);
    }

    private void downloadFileInternal(String urlString, Path targetPath, int redirectCount) throws IOException {
        if (redirectCount > MAX_REDIRECTS) {
            throw new IOException("Too many redirects while downloading: " + urlString);
        }

        log.debug("Downloading: {} -> {}", urlString, targetPath);

        // 使用 URI.create().toURL() 替代已 deprecated 的 new URL(String)（JEP 20+ 推荐）
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(HttpClientConstants.CONNECT_TIMEOUT);
            conn.setReadTimeout(HttpClientConstants.READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", USER_AGENT);

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                String newUrl = conn.getHeaderField("Location");
                conn.disconnect();
                downloadFileInternal(newUrl, targetPath, redirectCount + 1);
                return;
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new HttpStatusException(responseCode, urlString);
            }

            Files.createDirectories(targetPath.getParent());
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(targetPath.toFile())) {
                byte[] buffer = new byte[HttpClientConstants.BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 解压 ZIP 到目标目录。
     *
     * <p>带 Zip Slip 防护：任何指向 {@code targetDir} 之外的条目都会触发异常。
     *
     * <p><b>路径规范化</b>：{@code targetDir} 会先 {@link Path#toAbsolutePath()}
     * 再 {@link Path#normalize()}，以避免相对路径 / {@code ..} 片段造成的 {@link Path#startsWith}
     * 比对误判（例如 {@code targetDir="foo/./bar"} 与 {@code entryPath="/abs/foo/bar/x"} 的不对称）。
     *
     * @param zipFile   ZIP 文件
     * @param targetDir 解压目标目录
     * @throws IOException 磁盘 IO 错误或安全检查失败
     */
    public void unzipFile(Path zipFile, Path targetDir) throws IOException {
        log.debug("Unzipping: {} -> {}", zipFile, targetDir);
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedTarget);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = normalizedTarget.resolve(entry.getName()).normalize();

                if (!entryPath.startsWith(normalizedTarget)) {
                    throw new IOException("Zip Slip blocked: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (FileOutputStream fos = new FileOutputStream(entryPath.toFile())) {
                        byte[] buffer = new byte[HttpClientConstants.BUFFER_SIZE];
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * 递归复制目录内容。
     *
     * <p>目标文件若已存在会被 {@link StandardCopyOption#REPLACE_EXISTING} 覆盖。
     *
     * @param source 源目录
     * @param target 目标目录
     * @throws IOException 复制失败
     */
    public void copyDirectory(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            walk.forEach(sourcePath -> {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy: " + sourcePath, e);
                }
            });
        }
    }

    /**
     * 递归删除目录及其所有内容。幂等：目录不存在时直接返回。
     *
     * @param dir 要删除的目录
     * @throws IOException IO 错误
     */
    public void deleteDirectory(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path);
                        }
                    });
        }
    }

    /**
     * HTTP 下载时收到非 2xx 响应的强类型异常。
     *
     * <p>相较于原先只在消息中体现状态码，本异常把 {@link #statusCode} 作为一等字段暴露，
     * 调用方可以基于状态码做确定性分支（如 {@code PluginInstaller} 对 GitHub 404 的
     * 分支 fallback），避免字符串解析带来的脆弱性。
     */
    public static class HttpStatusException extends IOException {

        /** HTTP 响应状态码 */
        private final int statusCode;

        /**
         * 构造异常。
         *
         * @param statusCode HTTP 响应码（如 404）
         * @param url        触发异常的请求 URL
         */
        public HttpStatusException(int statusCode, String url) {
            super("Download failed, HTTP " + statusCode + " for " + url);
            this.statusCode = statusCode;
        }

        /** @return HTTP 响应状态码 */
        public int getStatusCode() {
            return statusCode;
        }
    }
}
