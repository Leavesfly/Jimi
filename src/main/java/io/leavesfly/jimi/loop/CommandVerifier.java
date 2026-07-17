package io.leavesfly.jimi.loop;

import io.leavesfly.jimi.config.info.LoopEngineeringConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 确定性目标验证器
 * <p>
 * 通过执行用户提供的验证命令（如 "mvn -q test"），以进程退出码作为
 * 目标是否达成的硬证据：退出码 0 = 满足，非 0 = 不满足。
 * <p>
 * 这是 Maker/Checker 分离的确定性通道——验证依据是客观命令执行结果，
 * 而非执行者自己写入状态文件的自述，避免"自己批改自己作业"。
 */
@Slf4j
@Service
public class CommandVerifier {

    /**
     * 验证输出最多保留的字符数（取输出尾部，通常错误信息在末尾）
     */
    private static final int MAX_OUTPUT_CHARS = 2000;

    @Autowired
    private LoopEngineeringConfig config;

    /**
     * 执行验证命令并基于退出码判断目标是否满足
     *
     * @param command 验证命令（如 "mvn -q test"）
     * @param workDir 命令执行目录（通常是 worktree 或项目根目录）
     * @return 验证结果，satisfied = (退出码 == 0)
     */
    public GoalVerification verify(String command, Path workDir) {
        if (command == null || command.isBlank()) {
            return GoalVerification.notSatisfied("验证命令为空");
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder pb = windows
                ? new ProcessBuilder("cmd", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        long timeoutSeconds = config.getGoalVerifyCommandTimeoutSeconds();
        try {
            long start = System.currentTimeMillis();
            Process process = pb.start();
            String output = readProcessOutput(process);

            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("Verify command timed out after {}s: {}", timeoutSeconds, command);
                return GoalVerification.notSatisfied(
                        String.format("验证命令超时（%ds）: %s", timeoutSeconds, command));
            }

            int exitCode = process.exitValue();
            long elapsedMs = System.currentTimeMillis() - start;
            String tail = tail(output);

            if (exitCode == 0) {
                log.info("Verify command passed ({}ms): {}", elapsedMs, command);
                return GoalVerification.satisfied(
                        String.format("验证命令退出码为 0（%dms）: %s", elapsedMs, command));
            }

            log.info("Verify command failed (exit={}): {}", exitCode, command);
            return GoalVerification.notSatisfied(
                    String.format("验证命令退出码 %d: %s\n输出尾部:\n%s", exitCode, command, tail));

        } catch (IOException e) {
            log.error("Failed to launch verify command: {}", command, e);
            return GoalVerification.notSatisfied("验证命令启动失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GoalVerification.notSatisfied("验证命令被中断");
        }
    }

    private String readProcessOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "";
        }
    }

    private String tail(String output) {
        if (output == null || output.isEmpty()) {
            return "(无输出)";
        }
        return output.length() <= MAX_OUTPUT_CHARS
                ? output
                : "...\n" + output.substring(output.length() - MAX_OUTPUT_CHARS);
    }
}
