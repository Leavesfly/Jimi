package io.leavesfly.jimi.tool.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能安装器
 * 
 * 支持从多种来源安装技能：
 * - GitHub 仓库：owner/repo 或 owner/repo/skill-name
 * - 压缩包 URL：.zip 文件
 * 
 * 安装流程：
 * 1. 下载/克隆技能文件
 * 2. 解压到临时目录
 * 3. 验证 SKILL.md 存在
 * 4. 复制到用户技能目录 (~/.jimi/skills/)
 * 5. 注册到 SkillRegistry
 */
@Slf4j
@Service
public class SkillsInstaller {

    private static final String GITHUB_RAW_BASE = "https://raw.githubusercontent.com";
    private static final String GITHUB_ARCHIVE_BASE = "https://github.com";
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000;

    @Autowired
    private SkillLoader skillLoader;

    @Autowired
    private SkillRegistry skillRegistry;

    /**
     * 从 GitHub 仓库安装技能
     * 
     * 支持格式：
     * - owner/repo：安装仓库根目录的技能
     * - owner/repo/skill-name：安装仓库中指定子目录的技能
     * 
     * @param repoSpec GitHub 仓库规格
     * @return 安装的 SkillSpec
     */
    public SkillSpec installFromGitHub(String repoSpec) {
        if (repoSpec == null || repoSpec.trim().isEmpty()) {
            throw new IllegalArgumentException("GitHub 仓库规格不能为空");
        }

        // 解析仓库规格
        String[] parts = repoSpec.split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "无效的 GitHub 仓库格式。期望格式：owner/repo 或 owner/repo/skill-name"
            );
        }

        String owner = parts[0];
        String repo = parts[1];
        String skillPath = parts.length > 2 ? parts[2] : "";

        log.info("从 GitHub 安装技能: owner={}, repo={}, path={}", owner, repo, skillPath);

        try {
            // 下载仓库 ZIP 归档
            String archiveUrl = String.format("%s/%s/%s/archive/refs/heads/main.zip",
                    GITHUB_ARCHIVE_BASE, owner, repo);

            Path tempDir = Files.createTempDirectory("skill-install-");
            Path zipFile = tempDir.resolve("repo.zip");

            try {
                // 下载 ZIP 文件
                downloadFile(archiveUrl, zipFile);

                // 解压
                Path extractDir = tempDir.resolve("extracted");
                unzipFile(zipFile, extractDir);

                // 找到实际的技能目录
                Path skillDir = findSkillDirectory(extractDir, repo, skillPath);
                if (skillDir == null) {
                    throw new RuntimeException("在仓库中未找到 SKILL.md 文件");
                }

                // 安装技能
                return installFromDirectory(skillDir);

            } finally {
                // 清理临时文件
                deleteDirectory(tempDir);
            }

        } catch (Exception e) {
            log.error("从 GitHub 安装技能失败: {}", repoSpec, e);
            throw new RuntimeException("从 GitHub 安装技能失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 URL 安装技能（压缩包）
     * 
     * @param url 压缩包 URL
     * @param skillName 技能名称（可选，用于重命名）
     * @return 安装的 SkillSpec
     */
    public SkillSpec installFromUrl(String url, String skillName) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("URL 不能为空");
        }

        log.info("从 URL 安装技能: url={}, name={}", url, skillName);

        try {
            Path tempDir = Files.createTempDirectory("skill-install-");
            Path zipFile = tempDir.resolve("skill.zip");

            try {
                // 下载文件
                downloadFile(url, zipFile);

                // 解压
                Path extractDir = tempDir.resolve("extracted");
                unzipFile(zipFile, extractDir);

                // 找到技能目录
                Path skillDir = findSkillDirectoryInExtracted(extractDir);
                if (skillDir == null) {
                    throw new RuntimeException("在压缩包中未找到 SKILL.md 文件");
                }

                // 如果指定了名称，重命名技能目录
                if (skillName != null && !skillName.trim().isEmpty()) {
                    Path renamedDir = skillDir.getParent().resolve(skillName.trim());
                    if (!skillDir.equals(renamedDir)) {
                        Files.move(skillDir, renamedDir);
                        skillDir = renamedDir;
                    }
                }

                // 安装技能
                return installFromDirectory(skillDir);

            } finally {
                // 清理临时文件
                deleteDirectory(tempDir);
            }

        } catch (Exception e) {
            log.error("从 URL 安装技能失败: {}", url, e);
            throw new RuntimeException("从 URL 安装技能失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从本地目录安装技能
     */
    private SkillSpec installFromDirectory(Path skillDir) {
        // 验证 SKILL.md 存在
        Path skillFile = skillDir.resolve("SKILL.md");
        if (!Files.exists(skillFile)) {
            throw new RuntimeException("技能目录中缺少 SKILL.md 文件: " + skillDir);
        }

        // 加载技能以获取元信息
        SkillSpec skill = skillLoader.loadSkillFromPath(skillDir);
        if (skill == null) {
            throw new RuntimeException("无法解析技能文件: " + skillFile);
        }

        // 复制到用户技能目录
        Path userSkillsDir = skillLoader.getUserSkillsDirectory();
        Path targetDir = userSkillsDir.resolve(skill.getName());

        try {
            // 创建目标目录
            Files.createDirectories(targetDir);

            // 复制所有文件
            copyDirectory(skillDir, targetDir);

            // 更新技能路径并注册
            skill.setSkillFilePath(targetDir.resolve("SKILL.md"));
            skill.setScope(SkillSpec.SkillScope.GLOBAL);
            skillRegistry.register(skill);

            log.info("技能安装成功: {} -> {}", skill.getName(), targetDir);
            return skill;

        } catch (Exception e) {
            // 清理失败的安装
            try {
                deleteDirectory(targetDir);
            } catch (Exception ignored) {}
            throw new RuntimeException("复制技能文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载文件
     */
    private void downloadFile(String urlString, Path targetPath) throws Exception {
        log.debug("下载文件: {} -> {}", urlString, targetPath);

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", "Jimi-SkillsInstaller/1.0");

        // 处理重定向
        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
            responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
            responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
            String newUrl = conn.getHeaderField("Location");
            conn.disconnect();
            downloadFile(newUrl, targetPath);
            return;
        }

        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("下载失败，HTTP 状态码: " + responseCode);
        }

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(targetPath.toFile())) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 解压 ZIP 文件
     */
    private void unzipFile(Path zipFile, Path targetDir) throws Exception {
        log.debug("解压文件: {} -> {}", zipFile, targetDir);
        Files.createDirectories(targetDir);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();

                // 安全检查：防止 Zip Slip 攻击
                if (!entryPath.startsWith(targetDir)) {
                    throw new RuntimeException("ZIP 条目超出目标目录: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (FileOutputStream fos = new FileOutputStream(entryPath.toFile())) {
                        byte[] buffer = new byte[BUFFER_SIZE];
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
     * 在 GitHub 归档中找到技能目录
     */
    private Path findSkillDirectory(Path extractDir, String repo, String skillPath) throws Exception {
        // GitHub 归档解压后的目录结构：repo-main/...
        Path[] candidates = Files.list(extractDir)
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith(repo))
                .toArray(Path[]::new);

        if (candidates.length == 0) {
            return null;
        }

        Path repoDir = candidates[0];

        // 如果指定了子路径
        if (skillPath != null && !skillPath.isEmpty()) {
            repoDir = repoDir.resolve(skillPath);
        }

        // 检查是否有 SKILL.md
        if (Files.exists(repoDir.resolve("SKILL.md"))) {
            return repoDir;
        }

        // 尝试在子目录中查找
        return findSkillDirectoryInExtracted(repoDir);
    }

    /**
     * 在解压目录中递归查找技能目录
     */
    private Path findSkillDirectoryInExtracted(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return null;
        }

        // 直接检查当前目录
        if (Files.exists(dir.resolve("SKILL.md"))) {
            return dir;
        }

        // 递归检查子目录（最多两层）
        Path[] subDirs = Files.list(dir)
                .filter(Files::isDirectory)
                .toArray(Path[]::new);

        for (Path subDir : subDirs) {
            if (Files.exists(subDir.resolve("SKILL.md"))) {
                return subDir;
            }
            // 再检查一层
            Path[] deepDirs = Files.list(subDir)
                    .filter(Files::isDirectory)
                    .toArray(Path[]::new);
            for (Path deepDir : deepDirs) {
                if (Files.exists(deepDir.resolve("SKILL.md"))) {
                    return deepDir;
                }
            }
        }

        return null;
    }

    /**
     * 复制目录
     */
    private void copyDirectory(Path source, Path target) throws Exception {
        Files.walk(source).forEach(sourcePath -> {
            try {
                Path targetPath = target.resolve(source.relativize(sourcePath));
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.copy(sourcePath, targetPath, 
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new RuntimeException("复制文件失败: " + sourcePath, e);
            }
        });
    }

    /**
     * 删除目录及其内容
     */
    private void deleteDirectory(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (Exception e) {
                        log.warn("删除文件失败: {}", path);
                    }
                });
    }
}
