package io.leavesfly.jimi.soul.runtime;

import io.leavesfly.jimi.config.JimiConfig;
import io.leavesfly.jimi.llm.LLM;
import io.leavesfly.jimi.session.Session;
import io.leavesfly.jimi.soul.approval.Approval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Runtime 运行时上下文
 * 包含 Agent 运行所需的所有全局状态和服务
 * <p>
 * 功能特性：
 * 1. 配置管理（JimiConfig）
 * 2. 会话信息（Session）
 * 3. 内置参数（BuiltinSystemPromptArgs）
 * 5. 审批服务（Approval）
 * 6. 工作目录信息加载
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Runtime {

    /**
     * 全局配置
     */
    private JimiConfig config;

    /**
     * LLM 实例
     */
    private LLM llm;

    /**
     * 会话信息
     */
    private Session session;

    /**
     * 内置系统提示词参数
     */
    private BuiltinSystemPromptArgs builtinArgs;


    /**
     * 审批机制
     */
    private Approval approval;

    /**
     * 创建 Runtime 实例
     *
     * @param config  全局配置
     * @param llm     LLM实例
     * @param session 会话信息
     * @param yolo    是否启用YOLO模式（自动批准所有审批）
     * @return Runtime实例的Mono
     */
    public static Mono<Runtime> create(
            JimiConfig config,
            LLM llm,
            Session session,
            boolean yolo
    ) {
        return Mono.fromCallable(() -> {
            // 获取工作目录列表
            String lsOutput = listWorkDir(session.getWorkDir());

            // 加载 AGENTS.md
            String agentsMd = loadAgentsMd(session.getWorkDir());

            // 构建内置参数
            BuiltinSystemPromptArgs builtinArgs = BuiltinSystemPromptArgs.builder()
                    .kimiNow(ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .kimiWorkDir(session.getWorkDir())
                    .kimiWorkDirLs(lsOutput)
                    .kimiAgentsMd(agentsMd)
                    .build();

            // 创建 Runtime
            return Runtime.builder()
                    .config(config)
                    .llm(llm)
                    .session(session)
                    .builtinArgs(builtinArgs)
                    .approval(new Approval(yolo))
                    .build();
        });
    }

    /**
     * 列出工作目录内容
     * 跨平台支持：Windows使用dir，类 Unix系统使用ls -la
     */
    private static String listWorkDir(Path workDir) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;

            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "dir", workDir.toString());
            } else {
                pb = new ProcessBuilder("ls", "-la", workDir.toString());
            }

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            process.waitFor();
            return output.toString().trim();
        } catch (IOException | InterruptedException e) {
            log.error("Failed to list work directory", e);
            return "";
        }
    }

    /**
     * 加载 AGENTS.md 文件
     * 按顺序尝试 AGENTS.md 和 agents.md
     *
     * @param workDir 工作目录
     * @return AGENTS.md的内容，如果不存在则返回空字符串
     */
    private static String loadAgentsMd(Path workDir) {
        Path[] paths = {
                workDir.resolve("AGENTS.md"),
                workDir.resolve("agents.md")
        };

        for (Path path : paths) {
            if (Files.isRegularFile(path)) {
                try {
                    String content = Files.readString(path);
                    log.info("Loaded AGENTS.md from: {}", path);
                    return content.trim();
                } catch (IOException e) {
                    log.warn("Failed to read AGENTS.md from: {}", path, e);
                }
            }
        }

        log.info("No AGENTS.md found in work directory: {}", workDir);
        return "";
    }

    /**
     * 获取工作目录
     */
    public Path getWorkDir() {
        return session.getWorkDir();
    }

    /**
     * 获取会话 ID
     */
    public String getSessionId() {
        return session.getId();
    }

    /**
     * 检查是否为YOLO模式
     */
    public boolean isYoloMode() {
        return approval != null && approval.isYolo();
    }

    /**
     * 重新加载工作目录信息
     * 用于当工作目录发生变化时更新
     */
    public Mono<Void> reloadWorkDirInfo() {
        return Mono.fromRunnable(() -> {
            log.debug("重新加载工作目录信息");
            String lsOutput = listWorkDir(session.getWorkDir());
            String agentsMd = loadAgentsMd(session.getWorkDir());

            this.builtinArgs.setKimiWorkDirLs(lsOutput);
            this.builtinArgs.setKimiAgentsMd(agentsMd);
            this.builtinArgs.setKimiNow(
                    ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            );
        });
    }
}
