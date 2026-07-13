package io.leavesfly.jimi.loop;

import io.leavesfly.jimi.config.info.LoopEngineeringConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Git Worktree 生命周期管理器
 * <p>
 * 为多 Agent 并行执行提供文件系统级别的隔离。
 * 每个 Agent 在独立的 git worktree 中工作，避免文件冲突。
 */
@Slf4j
@Service
public class WorktreeManager {

    @Autowired
    private LoopEngineeringConfig config;

    /**
     * 为指定 Agent 创建隔离的 worktree
     *
     * @param agentName Agent 名称（用于分支命名）
     * @param baseDir   主仓库目录
     * @return worktree 的工作目录路径
     * @throws IOException 创建失败时抛出
     */
    public Path createWorktree(String agentName, Path baseDir) throws IOException {
        if (!isGitRepo(baseDir)) {
            throw new IOException("Not a git repository: " + baseDir);
        }

        String branchSuffix = UUID.randomUUID().toString().substring(0, 8);
        String branchName = config.getWorktreeBranchPrefix() + agentName + "-" + branchSuffix;
        Path worktreeDir = baseDir.resolve(config.getWorktreeBaseDir()).resolve(agentName);

        // 确保目录不存在
        if (Files.exists(worktreeDir)) {
            cleanupWorktree(worktreeDir, baseDir);
        }

        // 创建 worktree
        int exitCode = executeGitCommand(baseDir,
                "git", "worktree", "add", "-b", branchName,
                worktreeDir.toAbsolutePath().toString(), "HEAD");

        if (exitCode != 0) {
            throw new IOException("Failed to create worktree, exit code: " + exitCode);
        }

        log.info("Created worktree for agent '{}' at: {} (branch: {})", agentName, worktreeDir, branchName);
        return worktreeDir;
    }

    /**
     * 清理 worktree 并删除临时分支
     *
     * @param worktreeDir worktree 工作目录
     * @param baseDir     主仓库目录
     */
    public void cleanupWorktree(Path worktreeDir, Path baseDir) {
        try {
            // 获取分支名（在删除 worktree 之前）
            String branch = getWorktreeBranch(worktreeDir);

            // 移除 worktree
            executeGitCommand(baseDir,
                    "git", "worktree", "remove", "--force", worktreeDir.toAbsolutePath().toString());

            // 删除临时分支
            if (branch != null && branch.startsWith(config.getWorktreeBranchPrefix())) {
                executeGitCommand(baseDir, "git", "branch", "-D", branch);
            }

            log.info("Cleaned up worktree: {}", worktreeDir);
        } catch (Exception e) {
            log.error("Failed to cleanup worktree: {}", worktreeDir, e);
        }
    }

    /**
     * 合并 worktree 的变更到目标分支
     *
     * @param worktreeDir  worktree 工作目录
     * @param baseDir      主仓库目录
     * @param targetBranch 目标分支
     * @return 合并结果
     */
    public MergeResult mergeWorktree(Path worktreeDir, Path baseDir, String targetBranch) {
        try {
            String branch = getWorktreeBranch(worktreeDir);
            if (branch == null) {
                return MergeResult.failure("Cannot determine worktree branch");
            }

            // 切换到目标分支
            int checkoutExit = executeGitCommand(baseDir, "git", "checkout", targetBranch);
            if (checkoutExit != 0) {
                return MergeResult.failure("Failed to checkout target branch: " + targetBranch);
            }

            // 合并
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "merge", "--no-ff", branch, "-m",
                    "Merge agent work from " + branch);
            pb.directory(baseDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = readProcessOutput(process);
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                return MergeResult.failure("Merge timed out");
            }

            if (process.exitValue() != 0) {
                return MergeResult.failure("Merge conflict: " + output);
            }

            return MergeResult.success("Merged " + branch + " into " + targetBranch);

        } catch (Exception e) {
            return MergeResult.failure("Merge error: " + e.getMessage());
        }
    }

    /**
     * 列出当前活跃的 worktrees
     *
     * @param baseDir 主仓库目录
     * @return worktree 信息列表
     */
    public List<WorktreeInfo> listWorktrees(Path baseDir) {
        List<WorktreeInfo> result = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "list", "--porcelain");
            pb.directory(baseDir.toFile());
            Process process = pb.start();

            String output = readProcessOutput(process);
            process.waitFor(10, TimeUnit.SECONDS);

            // 解析 porcelain 输出
            String currentDir = null;
            String currentBranch = null;
            for (String line : output.split("\n")) {
                if (line.startsWith("worktree ")) {
                    currentDir = line.substring("worktree ".length());
                } else if (line.startsWith("branch ")) {
                    currentBranch = line.substring("branch refs/heads/".length());
                } else if (line.isEmpty() && currentDir != null) {
                    result.add(new WorktreeInfo(currentBranch, Path.of(currentDir)));
                    currentDir = null;
                    currentBranch = null;
                }
            }
            // 处理最后一个条目
            if (currentDir != null) {
                result.add(new WorktreeInfo(currentBranch, Path.of(currentDir)));
            }

        } catch (Exception e) {
            log.error("Failed to list worktrees: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 检查指定目录是否为 git 仓库
     *
     * @param dir 目录路径
     * @return 是否为 git 仓库
     */
    public boolean isGitRepo(Path dir) {
        return Files.exists(dir.resolve(".git")) || Files.exists(dir.resolve(".git/HEAD"));
    }

    // ==================== 内部方法 ====================

    private String getWorktreeBranch(Path worktreeDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.directory(worktreeDir.toFile());
            Process process = pb.start();
            String output = readProcessOutput(process).trim();
            process.waitFor(5, TimeUnit.SECONDS);
            return output.isEmpty() ? null : output;
        } catch (Exception e) {
            log.debug("Failed to get worktree branch: {}", e.getMessage());
            return null;
        }
    }

    private int executeGitCommand(Path workDir, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            readProcessOutput(process); // 消耗输出防止阻塞
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (Exception e) {
            log.error("Git command failed: {}", String.join(" ", command), e);
            return -1;
        }
    }

    private String readProcessOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "";
        }
    }

    // ==================== 辅助类 ====================

    /**
     * 合并结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MergeResult {
        private boolean success;
        private String message;

        public static MergeResult success(String message) {
            return new MergeResult(true, message);
        }

        public static MergeResult failure(String message) {
            return new MergeResult(false, message);
        }
    }

    /**
     * Worktree 信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorktreeInfo {
        private String branch;
        private Path dir;
    }
}
